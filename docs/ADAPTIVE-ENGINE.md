# PATIPP — Adaptive Learning Engine

This is the part of the product that is not a quiz app. It lives in `com.patipp.adaptive`
and `com.patipp.scheduling`, has **no Spring, JPA, or web dependencies**, and takes plain
records in and returns plain records out — so every rule below is unit-testable in
milliseconds without a database.

---

## 0. Five design principles

1. **Separable.** Scheduling, selection, difficulty and readiness are four interfaces, not
   one tangled service. Any one can be replaced without touching the other three.
2. **Versioned.** Every implementation carries a version key (`FSRS_V1`, `ELO_TARGETED_V1`,
   `WEIGHTED_V1`) which is stamped onto the rows it writes. You can always tell which
   algorithm produced a number.
3. **Pure.** Given the same `LearnerModel` input, the engine returns the same output. All
   I/O happens outside it. This is what makes the replay harness (section 8) possible.
4. **Explainable.** Every selected question carries a `selection_reason`. Every readiness
   score carries its component breakdown. If the engine cannot explain itself, you will
   stop trusting it, and then the feature is dead.
5. **Kind.** An adaptive system that always pushes you to the edge of failure is one you
   stop opening. Guardrails (section 4.3) are a feature, not a safety net.

---

## 1. The LearnerModel

A read-only snapshot built per `(user, preparation_space)`, cached for the duration of a
session. Everything downstream reads only this.

```java
public record LearnerModel(
    UUID userId,
    UUID spaceId,
    double globalAbility,                       // Elo, seeded at 1200
    Map<UUID, TopicState> topics,               // topicId -> state
    Map<UUID, ItemState> items,                 // questionId -> review state
    RecentWindow recent,                        // last 20 attempts, ordered
    Coverage coverage,                          // seen / total per topic and subject
    Instant asOf
) {}

public record TopicState(
    UUID topicId, UUID subjectId, double weight,
    double ability,            // Elo, learner side, per topic
    double accuracy,           // lifetime
    double decayedAccuracy,    // exponentially recency-weighted
    int attempts, int correct,
    double coverage,           // fraction of the topic's active questions ever attempted
    Instant lastPracticedAt,
    MasteryLevel level
) {}
```

**Decayed accuracy** is the honest measure. Lifetime accuracy tells you what you knew in
March; it barely moves after a few hundred attempts and cannot detect that you have gone
rusty. We weight each attempt by `0.5^(ageDays / 14)` — a 14-day half-life — so the number
reflects your *current* state, which is what "am I ready?" actually asks.

---

## 2. Ability estimation — Elo, both sides

Full IRT (2PL/3PL) needs thousands of responses per item to fit. You will have a few dozen.
Elo is the right tool: it converges fast, is robust to sparse data, updates in O(1) per
attempt, and is trivially explainable.

Two ratings move on every attempt: the learner's ability in that topic, and the question's
difficulty.

**Expected probability that the learner answers correctly:**

```
E = 1 / (1 + 10^((R_item - R_learner) / 400))
```

**Updates** (`S` = 1.0 correct, 0.0 wrong, or the partial-credit score in between):

```
R_learner' = R_learner + K_L * (S - E)
R_item'    = R_item    + K_I * (E - S)
```

`K_L` decays with experience so early attempts move the estimate fast and later ones refine
it:

| Attempts in topic | `K_L` |
|---|---|
| < 10 | 48 |
| 10–29 | 32 |
| 30–99 | 20 |
| 100+ | 12 |

`K_I = 8`, and drops to `4` after 30 ratings — item difficulty should stabilise, since the
question itself is not changing.

**Seeding.** A new question's rating comes from its authored difficulty label:
`EASY 1000, MEDIUM 1200, HARD 1400, EXPERT 1600`. A new learner starts at 1200 in every
topic. The authored label is therefore a *prior*, and real responses correct it — which
means a question you mislabelled as EASY quietly fixes itself after a handful of attempts.
That is a genuinely nice property and costs nothing.

Both ratings are per-space: `topic_mastery.ability` and `question_stats.elo_rating`.

---

## 3. Spaced repetition (`scheduling`)

### 3.1 The model

A simplified stability/difficulty scheme behind the `ReviewScheduler` interface. Full
FSRS-5 with its 17 fitted parameters is a legitimate future upgrade — the interface exists
precisely so that swap is a one-class change.

Two per-item variables in `learning_states`:

- **Stability `S`** — days until recall probability decays to 90%.
- **Difficulty `D`** — intrinsic hardness for *this learner*, on `[1, 10]`.

**Retrievability** after `t` days:

```
R(t) = 0.9 ^ (t / S)
```

**Next interval** for a target retention `r` (default 0.90, configurable per space):

```
I = S * ln(r) / ln(0.9)          // r = 0.90 -> I = S
```

### 3.2 Updates

Grade `g` in `{1 Again, 2 Hard, 3 Good, 4 Easy}`.

```
D' = clamp(D + 0.55 * (3 - g), 1, 10)

on success (g >= 2):
    S' = S * (1 + (2.6 - 0.16 * D') * gradeMult(g) * (1 + 0.4 * (1 - R)))
         where gradeMult = { 2: 0.55, 3: 1.0, 4: 1.35 }

on lapse (g == 1):
    S' = max(0.4, S * 0.42 * (1 - 0.045 * D'))
    lapses += 1
    state  = RELEARNING
```

The `(1 + 0.4 * (1 - R))` term is the spacing effect: recalling something you had *almost*
forgotten strengthens it far more than recalling something you reviewed an hour ago. Without
it the scheduler rewards cramming.

New items: `S = 1.0`, `D = 5.0 - 0.8 * (label ordinal - 1)` (harder labels start harder),
learning steps at 10 minutes and 1 day before entering `REVIEW`.

### 3.3 Deriving a grade from an ordinary quiz answer

Flashcards give you an explicit Again/Hard/Good/Easy. MCQs do not — but they still need to
feed the scheduler, otherwise you have two disconnected systems. We derive the grade:

```
incorrect                                                     -> 1  (Again)
correct, but t > 2.0 * expected  OR  self-reported confidence <= 2
                                                              -> 2  (Hard)
correct, within normal time                                   -> 3  (Good)
correct, t < 0.5 * expected, and confidence = 4 (or unreported)-> 4  (Easy)
```

`expected` is `question.estimated_seconds`, refined by `question_stats.avg_response_ms`
once there are enough samples. Response time is the honest signal that separates "I knew
it" from "I worked it out" from "I guessed and got lucky" — and a lucky guess that is
scheduled as if it were solid knowledge is exactly how spaced repetition fails people.

### 3.4 Priority bands (your requirement 5, made concrete)

The bands you described map directly onto computed state:

| Band | Condition |
|---|---|
| **HIGH** | `lapses >= 2` OR `consecutive_incorrect >= 2` OR overdue by more than `S` days |
| **MEDIUM** | due now or overdue by less than `S`, `reps < 4` |
| **LOW** | `reps >= 4`, `consecutive_correct >= 3`, `S > 21` days |
| **SUSPENDED** | manually parked by the user |

---

## 4. Question selection

### 4.1 The pipeline

```
candidate pool
  = ACTIVE questions in space
    filtered by session config (topics, difficulty, types)
    filtered by blueprint allowedQuestionTypes
    minus items answered in the last 90 minutes
        |
        v
score every candidate  (section 4.2)
        |
        v
apply constraints      (section 4.3)
        |
        v
weighted sampling from the top 3N   (section 4.4)
        |
        v
N questions, each with a selection_reason
```

### 4.2 The composite score

Every component is normalised to `[0, 1]`. Weights come from the space blueprint, so an
interview space can weight weakness higher and retention lower than an exam space.

```
score = 0.30 * DueScore
      + 0.25 * WeaknessScore
      + 0.20 * DifficultyFit
      + 0.15 * CoverageGap
      + 0.10 * Freshness
      - penalties
```

| Component | Formula | Why |
|---|---|---|
| `DueScore` | due: `min(1, overdueDays / max(1, S))`; new item: `0.45`; not due: `0.05` | retention debt is the most time-critical thing you can do |
| `WeaknessScore` | `1 - decayedAccuracy(topic)`, `+0.2` if `consecutive_incorrect >= 2` | this is what makes React Native at 49% outrank JavaScript at 92% |
| `DifficultyFit` | `exp(-((E - target)^2) / (2 * 0.15^2))` where `E` is the Elo expectation | Gaussian around the target success rate — section 5 |
| `CoverageGap` | `1 - coverage(topic)`, scaled by `topic.weight` | stops the engine drilling three known topics and ignoring five untouched ones |
| `Freshness` | `1 - exp(-daysSinceLastSeen / 7)` | anti-repetition; a question seen yesterday should rarely reappear today |

**Penalties:** `-0.5` if the same topic already occupies 40% of the session;
`-0.3` per prior appearance today; `-0.25` if the previous two items were the same topic.

### 4.3 Constraints and guardrails

Applied after scoring, before sampling:

- **Diversity** — no single topic exceeds 40% of a session (unless the user explicitly
  chose a single-topic drill).
- **Retention floor** — at least one due-review item per five questions, so the review
  backlog never starves behind new material.
- **No hard runs** — never three consecutive `EXPERT` items.
- **Recovery mode** — if at most one of the last five is correct, drop the target success
  rate to 0.90, serve from the learner's strongest available topic, and hold for three
  items before resuming. This is the "do not make the system frustrating" requirement,
  implemented rather than aspired to.
- **Win cadence** — guarantee at least one item with `E > 0.85` every six questions.
- **Exam mode overrides all of it.** An exam samples to the *blueprint weights*
  (React 30%, CSS 15%, …) at authored difficulty, because a mock exam that adapts is not a
  mock exam — it must be a stable yardstick you can compare across weeks.

### 4.4 Sampling, not ranking

Taking the top N deterministically means the same session every morning. Instead: take the
top `3N` by score, then sample `N` without replacement with probability proportional to
`exp(score / T)`, temperature `T = 0.3`. High scorers still dominate; the session stays
varied. `T` is configurable, and `T = 0` gives you deterministic selection for tests.

---

## 5. Adaptive difficulty

### The target

Aim for a **78% success rate**. This is the well-supported sweet spot for learning
efficiency: high enough to stay motivating, low enough that you are meeting genuine
resistance. Blueprints may override it (`EASY` onboarding modes use 0.85).

From the Elo relation, a 78% expected success corresponds to:

```
R_item_target = R_learner - 400 * log10(0.78 / 0.22) = R_learner - 219
```

So the engine seeks questions rated roughly **220 points below** the learner's topic ability,
and `DifficultyFit` scores candidates by how close they land to that point.

### The ladder

`EASY -> MEDIUM -> HARD -> EXPERT` remains as an authoring vocabulary and a user-facing
filter, but the *engine* works in continuous Elo. The ladder is derived for display:

| Band | Rating relative to learner |
|---|---|
| EASY | `< R_L - 300` |
| MEDIUM | `R_L - 300` to `R_L - 100` |
| HARD | `R_L - 100` to `R_L + 150` |
| EXPERT | `> R_L + 150` |

The consequence is worth stating plainly: **a "HARD" question becomes a "MEDIUM" question
as you improve, without anyone relabelling anything.** Difficulty is relative to you, which
is the only definition that stays true over six months of study.

### Movement rules

- Three consecutive correct with `E > 0.85` -> raise the target rating by 40 points.
- Two consecutive incorrect with `E < 0.5` -> lower by 60 points (down faster than up).
- Ability is clamped to `+/- 250` movement within a single session, so one bad night does not
  wipe a month of calibration.

---

## 6. Weak topic detection

```
weakness = 0.45 * (1 - decayedAccuracy)
         + 0.25 * errorRecency          // 0.5^(daysSinceLastError / 5)
         + 0.15 * (1 - coverage)
         + 0.15 * lapseRate             // lapses / max(1, reps)
```

Ranked descending, with one non-negotiable rule:

> **A topic with fewer than 5 attempts is `UNASSESSED`, never `WEAK`.**

Calling a topic weak on two data points produces a study plan built on noise, and it
destroys trust in the recommendation the first time it is obviously wrong. Unassessed
topics are surfaced separately as *"needs assessment"* and get their own slice of the daily
plan — which is a genuinely different and more honest recommendation than "you are bad at
this."

Detection runs at both topic and subtopic level. The output is what drives
*"Focus on React Hooks"*: the subtopic that contributes the most weakness mass to a weak
parent topic.

---

## 7. Readiness score

Deliberately **not** average quiz percentage. Six components, each `0–100`, combined with
blueprint weights.

| Component | Measures | Formula sketch |
|---|---|---|
| **Coverage** | Have you seen the syllabus? | subject-weighted fraction of topics with `>= 5` attempts |
| **Accuracy** | Are you getting them right? | difficulty-weighted, recency-weighted correct rate |
| **Depth** | At the level you need? | mean `E` against items at the target difficulty for the space |
| **Retention** | Will you still know it on the day? | `1 - (overdue items / total review items)` |
| **Consistency** | Are you actually studying? | study days in the last 14 vs. target cadence |
| **Mock** | Under exam conditions? | recency-decayed best-of-3 recent `EXAM` session scores |

```
raw       = sum(weight_i * component_i)
readiness = raw * confidenceFactor
```

### The confidence factor — the part that keeps it honest

```
confidenceFactor = min(1.0, 0.55 + 0.45 * min(1, totalAttempts / 150))
```

After 12 questions you cannot be "88% ready" for anything, and a system that says so is
lying to you at the exact moment the stakes are highest. The factor caps early readiness
near 55–60% and displays *"Confidence: low — 12 of ~150 attempts"*. As you practise, the
ceiling lifts. This costs nothing to implement and is the difference between a number you
can act on and a vanity metric.

### Explainability

Every nightly snapshot stores `components`, `weights`, `deltas` and human-readable
`drivers`, so the UI can say:

> **Readiness 74% (+2 since yesterday).**
> Accuracy +1.4 (React Native 49% -> 58%). Retention −3.0 — 14 items are overdue.
> Biggest lever: clear the review backlog (+4 est.).

That last line — the biggest available lever — is the single most useful thing the whole
analytics module produces.

---

## 8. Cold start and evaluation

### Cold start

A brand-new space has no attempts, so every estimate is a prior. The first session is a
**calibration session**: 10–12 items spread across all topics at `MEDIUM`, using authored
difficulty only. Readiness displays *"Calibrating"* rather than a number. After the
calibration session the engine has a usable ability estimate per subject and switches to
normal operation.

### Knowing whether a change to the engine is an improvement

This is the part most projects skip, and it is why their algorithms drift into
superstition. Because attempts are an immutable event log and the engine is pure, we can
build a **replay harness** (Phase 5, part of the test suite):

- Replay the real attempt log through engine version A and version B.
- Compare on: **Brier score** of `E` versus actual outcome (is the ability model
  calibrated?), reviews-per-retained-item (is the scheduler efficient?), and coverage
  achieved per 100 questions.
- A new version ships only if it does not regress calibration.

Cheap to build once, and it turns "this feels better" into something you can check.

---

## 9. Interface summary

```java
public interface ReviewScheduler {                 // scheduling
    String version();
    ReviewState next(ReviewState current, Grade grade, Instant now, SchedulerConfig cfg);
    Priority priority(ReviewState state, Instant now);
}

public interface AbilityEstimator {                // adaptive
    String version();
    AbilityUpdate update(double learnerRating, double itemRating,
                         double outcome, int attemptsInTopic);
}

public interface QuestionSelector {                // adaptive
    String version();
    SelectionResult select(LearnerModel model, CandidatePool pool, SelectionRequest req);
}

public interface ReadinessModel {                  // analytics
    String version();
    ReadinessResult compute(LearnerModel model, SpaceBlueprint blueprint,
                            List<MockResult> mocks, Instant now);
}
```

Four interfaces. Every one of them is replaceable in isolation, every one is pure, and
every one is testable without starting Spring.

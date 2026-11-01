# 30 days of Java and Spring Boot

Goal: build evidence that you can write, test, explain, debug and rebuild a small application.
Thirty days is a practice window, not a promise of mastery.

## Daily routine

- **10–30 minutes:** recall yesterday's idea, write today's small core in your IDE, run its checks, explain one decision and log the attempt.
- **60–120 minutes:** also complete the optional extension, add tests and rebuild a portion from a blank file.
- If time runs out, record incomplete practice and one next step. A practice day counts toward consistency, while the exercise stays unfinished.
- Read only the resource section relevant to the current task. Try before requesting a hint. Generated or copied implementation counts as solution assistance; conceptual help counts as hints. Normal documentation lookup does not by itself make an attempt assisted.
- Compare the reference after committing a checked attempt to preserve independent completion. Opening it before submitting records solution assistance conservatively.

## Sequence

| Days | Focus | Evidence |
| --- | --- | --- |
| 1–7 | Java syntax, methods, records, lists, maps, debugging | A small console programme and an expense variant rebuilt from memory |
| 8–10 | Interfaces, injection, streams, JSON | A service boundary and sample JSON mapped to books |
| 11–17 | Spring wiring, GET/POST/DELETE, validation, tests | A checked book API and a product API rebuilt without copying |
| 18–24 | JPA, DTOs, transactions, persistence tests, errors, querying | A stored application with explicit HTTP behaviour |
| 25–30 | Debugging, transfer, optional timed drills, capstone | A task tracker variant, short interview rehearsals and a fresh rebuild |

Practice applications should use port **8082** so PATIPP keeps API **8081** and frontend **3001**.
Exercises are authored for modern Java; use the dependencies and imports managed by your chosen Spring Boot version. Reference snippets focus on the core task and may omit imports or show additions to earlier code; day 29 deliberately supplies a review outline for a learner-chosen feature.

## Architecture

The guide installs an ordinary CODING_TEST preparation space, Java/Spring subjects and topics,
and 30 versioned CODING questions from `backend/src/main/resources/challenges/java-spring-30.json`.
Each task has a small core, optional extension, public behavioural checks, a focused resource,
three authored hints and a reference. A later variant explicitly points back to an earlier day.

There is no new session engine or code runner. A guide starts a one-question PRACTICE session;
the optional final drills use EXAM. Attempts still feed existing mastery and review scheduling.
Guided exercise completion is separate from the aggregate readiness score.

Migration V11 stores hint count and solution reveal on the in-progress session item. Submission
copies assistance into the existing append-only answer JSON. The server supplies observed help;
the learner also reports external assistance, local checks and a reflection. Self-report is not
automated proof that code passed. Incomplete practice is recorded as an unsolved attempt.

## Verification

1. Open Spaces and start the challenge. Repeat enrollment: the same space and 30 questions remain.
2. Open day 1, code the core in an IDE, run the listed checks and submit an independent attempt.
3. Open a different day, reveal one hint, reload, and verify the hint remains revealed. Submit and verify HINTS in the guide.
4. Open a reference before submission and verify SOLUTION is recorded, even after reload.
5. Log an incomplete attempt: activity increases, completed exercises do not.
6. Return after signing out: server-backed attempts and progress remain.
7. On a later variant, recall the earlier task before implementing the new one.
8. Try a final timed drill: help is unavailable; the existing timer and deferred results apply.

Run backend `mvnw.cmd test` (Docker required), then frontend `npm run build` and `npm run lint`.

## Deliberate limits and follow-up

Completion and passing checks are self-reported; PATIPP never executes IDE code. No AI tutor,
push reminders or calendar notifications are required. The guide suggests authored variants;
the existing review scheduler continues to schedule individual questions. In-progress coding
drafts stay in the learner's IDE. Hint and solution use persist server-side; unsent reflection
and self-assessment are a browser draft. Activity dates use the profile timezone in the guide,
independently of the older analytics API's UTC daily buckets.

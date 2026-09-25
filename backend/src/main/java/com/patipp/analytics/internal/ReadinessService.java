package com.patipp.analytics.internal;

import com.patipp.analytics.domain.DailyActivity;
import com.patipp.analytics.domain.DailyActivityRepository;
import com.patipp.analytics.domain.ReadinessSnapshot;
import com.patipp.analytics.domain.ReadinessSnapshotRepository;
import com.patipp.analytics.model.ReadinessInputs;
import com.patipp.analytics.model.ReadinessInputs.ComponentWeights;
import com.patipp.analytics.model.ReadinessInputs.SubjectEvidence;
import com.patipp.analytics.model.ReadinessModel;
import com.patipp.analytics.model.ReadinessResult;
import com.patipp.learning.api.LearningAccess;
import com.patipp.preparations.api.BlueprintMerger;
import com.patipp.preparations.domain.PreparationSpace;
import com.patipp.preparations.domain.PreparationSpaceRepository;
import com.patipp.sessions.api.SessionHistoryAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the readiness inputs and records the snapshot.
 *
 * <p>The boundary between the pure model and everything else. Every judgement about what
 * readiness <em>means</em> lives in {@code analytics.model}; this class only gathers numbers from
 * the modules that own them — mastery from {@code learning}, review debt from the schedule, mock
 * results from the session history — and writes down what comes back.
 */
@Service
public class ReadinessService {

    /** How far back the consistency component looks. */
    private static final int CONSISTENCY_WINDOW_DAYS = 14;

    /** How many recent mocks the model considers. */
    private static final int RECENT_MOCKS = 3;

    private final ReadinessSnapshotRepository snapshots;
    private final DailyActivityRepository activity;
    private final ReadinessModel model;
    private final LearningAccess learning;
    private final SessionHistoryAccess mocks;
    private final PreparationSpaceRepository spaces;
    private final BlueprintMerger merger;
    private final Clock clock;

    public ReadinessService(ReadinessSnapshotRepository snapshots,
                            DailyActivityRepository activity,
                            ReadinessModel model,
                            LearningAccess learning,
                            SessionHistoryAccess mocks,
                            PreparationSpaceRepository spaces,
                            BlueprintMerger merger,
                            Clock clock) {
        this.snapshots = snapshots;
        this.activity = activity;
        this.model = model;
        this.learning = learning;
        this.mocks = mocks;
        this.spaces = spaces;
        this.merger = merger;
        this.clock = clock;
    }

    /**
     * Computes readiness now, and records it as today's snapshot.
     *
     * <p>Rewritten in place through the day rather than appended to, so the history is one point
     * per day. A readiness figure that moved four times before lunch is noise; the question worth
     * answering is how today compares with the last time the learner was measured.
     */
    @Transactional
    public ReadinessResult computeAndRecord(UUID userId, UUID spaceId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        ReadinessInputs inputs = gather(userId, spaceId, today);
        ComponentWeights weights = weightsFor(spaceId);

        // The previous snapshot, whenever it was. A learner who skipped four days should be told
        // how they compare with the last measurement, not with a day that has none.
        Map<String, Double> previous = snapshots
                .findPrevious(userId, spaceId, today, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(ReadinessSnapshot::componentsForDelta)
                .orElse(null);

        ReadinessResult result = model.compute(inputs, weights, previous);

        snapshots.findForDay(userId, spaceId, today).ifPresentOrElse(
                existing -> existing.update(result),
                () -> snapshots.save(ReadinessSnapshot.of(userId, spaceId, today, result, now)));

        return result;
    }

    /** Reads without recording, for anything that should not create a snapshot as a side effect. */
    @Transactional(readOnly = true)
    public ReadinessResult peek(UUID userId, UUID spaceId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return model.compute(gather(userId, spaceId, today), weightsFor(spaceId), null);
    }

    @Transactional(readOnly = true)
    public List<ReadinessSnapshot> history(UUID userId, UUID spaceId, int days) {
        return snapshots.findHistory(userId, spaceId,
                PageRequest.of(0, Math.clamp(days, 1, 365)));
    }

    /* ------------------------------------------------------------------ gathering */

    private ReadinessInputs gather(UUID userId, UUID spaceId, LocalDate today) {
        List<LearningAccess.TopicMasterySummary> mastery = learning.masteryFor(userId, spaceId);
        LearningAccess.ReviewDebt debt = learning.reviewDebt(userId, spaceId);
        LearningAccess.Coverage coverage = learning.coverageFor(userId, spaceId);

        List<SessionHistoryAccess.MockResult> recent =
                mocks.recentMocks(userId, spaceId, RECENT_MOCKS);
        List<Double> mockScores = new ArrayList<>();
        List<Integer> mockAges = new ArrayList<>();
        for (SessionHistoryAccess.MockResult mock : recent) {
            mockScores.add(mock.score());
            mockAges.add((int) java.time.temporal.ChronoUnit.DAYS.between(mock.on(), today));
        }

        return new ReadinessInputs(
                subjectEvidence(mastery, coverage),
                mastery.stream().mapToInt(LearningAccess.TopicMasterySummary::attempts).sum(),
                clock.instant(),
                spaces.findById(spaceId).map(PreparationSpace::targetDate).orElse(null),
                debt.due(),
                debt.struggling(),
                debt.tracked(),
                studyDays(userId, spaceId, today),
                targetStudyDays(spaceId),
                mockScores,
                mockAges,
                learning.depthExpectation(userId, spaceId));
    }

    /**
     * Rolls per-topic mastery up to per-subject evidence.
     *
     * <p>Per subject rather than per topic because that is the grain results are reported against,
     * and because the curriculum's weights live on subjects.
     */
    private List<SubjectEvidence> subjectEvidence(
            List<LearningAccess.TopicMasterySummary> mastery,
            LearningAccess.Coverage coverage) {

        Map<UUID, Accumulator> bySubject = new LinkedHashMap<>();

        for (LearningAccess.TopicMasterySummary row : mastery) {
            Accumulator acc = bySubject.computeIfAbsent(row.subjectId(),
                    id -> new Accumulator(coverage.subjectNames()
                            .getOrDefault(id, "Unassigned")));
            acc.add(row);
        }

        // Subjects with a curriculum entry but no attempts at all still count against coverage:
        // an untouched subject is the clearest possible gap, and leaving it out of the denominator
        // would let someone reach 100% coverage having never opened half the syllabus.
        coverage.subjectNames().forEach((subjectId, name) ->
                bySubject.computeIfAbsent(subjectId, id -> new Accumulator(name)));

        List<SubjectEvidence> evidence = new ArrayList<>();
        bySubject.forEach((subjectId, acc) -> evidence.add(new SubjectEvidence(
                acc.name,
                coverage.subjectWeights().getOrDefault(subjectId, 1.0),
                Math.max(acc.topicsSeen, coverage.topicCounts().getOrDefault(subjectId, 0)),
                acc.topicsAssessed,
                acc.decayedAccuracy(),
                // The real mean answered difficulty, from the attempt log. An earlier version
                // derived it from the mastery rollup, which does not carry difficulty at all and
                // so always produced exactly 2.0 — a weighting that looked applied and did
                // nothing whatsoever.
                coverage.meanDifficulty().getOrDefault(subjectId, 2.0),
                acc.attempts,
                acc.questionsSeen,
                coverage.questionCounts().getOrDefault(subjectId, 0))));

        return evidence;
    }

    /** Distinct days with at least one answer in this space over the consistency window. */
    private int studyDays(UUID userId, UUID spaceId, LocalDate today) {
        return (int) activity
                .findSpaceSince(userId, spaceId, today.minusDays(CONSISTENCY_WINDOW_DAYS - 1))
                .stream()
                .filter(day -> day.questionsAnswered() > 0)
                .count();
    }

    private int targetStudyDays(UUID spaceId) {
        Object defaults = settingsFor(spaceId).get("defaults");
        if (defaults instanceof Map<?, ?> map
                && map.get("studyDaysPerFortnight") instanceof Number number) {
            return number.intValue();
        }
        return ReadinessInputs.DEFAULT_TARGET_STUDY_DAYS;
    }

    /**
     * The blueprint's readiness weights.
     *
     * <p>This is how an interview space weights depth above coverage while a certification space
     * does the opposite, without either being a subclass of anything.
     */
    private ComponentWeights weightsFor(UUID spaceId) {
        Object raw = settingsFor(spaceId).get("readinessWeights");
        if (!(raw instanceof Map<?, ?> map)) {
            return ComponentWeights.standard();
        }

        Map<String, Double> weights = new HashMap<>();
        map.forEach((key, value) -> {
            if (value instanceof Number number) {
                weights.put(String.valueOf(key), number.doubleValue());
            }
        });
        return weights.isEmpty() ? ComponentWeights.standard() : new ComponentWeights(weights);
    }

    private Map<String, Object> settingsFor(UUID spaceId) {
        return spaces.findById(spaceId)
                .map(space -> merger.merge(space.preparationType().blueprint(), space.config()))
                .orElseGet(Map::of);
    }

    /** Scratch space for rolling topics up to a subject. */
    private static final class Accumulator {
        private final String name;
        private int attempts;
        private int topicsSeen;
        private int topicsAssessed;
        private int questionsSeen;
        private double weightedDecayed;

        Accumulator(String name) {
            this.name = name;
        }

        void add(LearningAccess.TopicMasterySummary row) {
            attempts += row.attempts();
            topicsSeen++;
            questionsSeen += row.questionsSeen();
            if (row.attempts() >= 5) {
                topicsAssessed++;
            }
            // Weighted by attempts: a topic with sixty answers should count for more in the
            // subject's accuracy than one with six.
            weightedDecayed += row.decayedAccuracy() * row.attempts();
        }

        double decayedAccuracy() {
            return attempts == 0 ? 0.0 : weightedDecayed / attempts;
        }

    }
}

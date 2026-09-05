package com.patipp.sessions.internal;

import com.patipp.adaptive.LearnerModel;
import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.QuestionSelector;
import com.patipp.adaptive.QuestionSelector.Candidate;
import com.patipp.adaptive.QuestionSelector.Chosen;
import com.patipp.adaptive.QuestionSelector.Selection;
import com.patipp.learning.api.LearningAccess;
import com.patipp.questions.api.QuestionAccess;
import com.patipp.questions.api.QuestionAccess.SelectionFilters;
import com.patipp.sessions.api.SessionDtos.StartSessionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the adaptive engine on behalf of the session engine.
 *
 * <p>Three modules meet here and nowhere else: {@code questions} supplies a pool of content,
 * {@code learning} supplies what is known about the learner, and {@code adaptive} decides.
 * Doing this inside {@code questions} instead - which an earlier note in {@code QuestionAccess}
 * proposed - would have made the content module depend on per-learner state, and the first
 * rule of this codebase is that user performance never attaches to a question.
 *
 * <p>The output is deliberately the same {@code SelectedQuestion} list the random and weighted
 * paths produce, so everything downstream is unchanged.
 */
@Component
public class AdaptiveSelection {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveSelection.class);

    private final QuestionAccess questions;
    private final LearningAccess learning;
    private final QuestionSelector selector;

    public AdaptiveSelection(QuestionAccess questions, LearningAccess learning,
                             QuestionSelector selector) {
        this.questions = questions;
        this.learning = learning;
        this.selector = selector;
    }

    public List<QuestionAccess.SelectedQuestion> select(UUID userId, UUID spaceId,
                                                        SelectionFilters filters, int length,
                                                        long seed,
                                                        Map<String, Object> effectiveSettings,
                                                        StartSessionRequest request) {
        List<QuestionAccess.Candidate> pool = questions.candidatesFor(spaceId, filters);
        if (pool.isEmpty()) {
            return List.of();
        }

        LearnerModel model = learning.modelFor(userId, spaceId);

        List<Candidate> candidates = new ArrayList<>(pool.size());
        for (QuestionAccess.Candidate candidate : pool) {
            candidates.add(new Candidate(
                    candidate.questionId(),
                    candidate.questionVersionId(),
                    TopicKey.of(candidate.subjectId(), candidate.topicId()),
                    candidate.rating(),
                    candidate.authoredDifficulty(),
                    candidate.estimatedSeconds()));
        }

        Selection selection = selector.select(model, candidates, new QuestionSelector.Request(
                length,
                seed,
                targetSuccessRate(effectiveSettings),
                QuestionSelector.Request.DEFAULT_TEMPERATURE,
                // Asking for one topic is an explicit instruction, and refusing it in the
                // name of diversity would be the engine overruling the person using it.
                isSingleTopicDrill(request)));

        log.debug("Adaptive selection for {} in {}: {} of {} candidates, {}",
                userId, spaceId, selection.items().size(), candidates.size(),
                selection.diagnostics());

        List<QuestionAccess.SelectedQuestion> selected = new ArrayList<>(selection.items().size());
        for (Chosen chosen : selection.items()) {
            selected.add(new QuestionAccess.SelectedQuestion(
                    chosen.questionId(), chosen.questionVersionId(),
                    reasonFor(chosen, selection)));
        }
        return selected;
    }

    /**
     * The reason recorded against the served item.
     *
     * <p>Written from the first session rather than added later, because "why this question?"
     * has to be answerable retrospectively and a reason reconstructed after the fact is a
     * guess. Nothing here gives an answer away.
     */
    private static Map<String, Object> reasonFor(Chosen chosen, Selection selection) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reason", chosen.reason());
        reason.put("why", chosen.explanation());
        reason.put("score", Math.round(chosen.score() * 1000) / 1000.0);
        reason.put("expectation", Math.round(chosen.expectation() * 1000) / 1000.0);
        reason.put("components", chosen.components());
        reason.put("engine", selection.diagnostics().get("version"));
        return reason;
    }

    /** A space may set its own target, so an onboarding mode can be gentler than 78%. */
    private static Double targetSuccessRate(Map<String, Object> effectiveSettings) {
        Object value = effectiveSettings == null ? null
                : effectiveSettings.get("targetSuccessRate");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean isSingleTopicDrill(StartSessionRequest request) {
        return (request.topicIds() != null && request.topicIds().size() == 1)
                || (request.subjectIds() != null && request.subjectIds().size() == 1
                        && (request.topicIds() == null || request.topicIds().isEmpty()));
    }
}

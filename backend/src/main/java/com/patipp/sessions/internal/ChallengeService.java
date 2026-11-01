package com.patipp.sessions.internal;

import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import com.patipp.curriculum.api.GuidedCurriculumAccess;
import com.patipp.preparations.api.ChallengeSpaceAccess;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.questions.api.GuidedQuestionAccess;
import com.patipp.questions.api.QuestionDtos;
import com.patipp.sessions.api.ChallengeDtos;
import com.patipp.sessions.api.SessionDtos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChallengeService {
    private final ChallengeCatalogue catalogue;
    private final ChallengeSpaceAccess spaces;
    private final GuidedCurriculumAccess curriculum;
    private final GuidedQuestionAccess questions;
    private final SpaceAccessGuard guard;
    private final QuestionAttemptRepository attempts;
    private final CurrentUser user;
    private final SessionService sessions;

    public ChallengeService(ChallengeCatalogue catalogue, ChallengeSpaceAccess spaces,
                            GuidedCurriculumAccess curriculum, GuidedQuestionAccess questions,
                            SpaceAccessGuard guard, QuestionAttemptRepository attempts,
                            CurrentUser user, SessionService sessions) {
        this.catalogue = catalogue; this.spaces = spaces; this.curriculum = curriculum;
        this.questions = questions; this.guard = guard; this.attempts = attempts;
        this.user = user; this.sessions = sessions;
    }

    /** One transaction: a failed installation leaves no half-created space or question pack. */
    @Transactional
    public ChallengeDtos.Enrollment enroll() {
        UUID spaceId = spaces.findOrCreate(ChallengeCatalogue.KEY, "30 days of Java and Spring Boot");
        guard.requireWritable(spaceId);
        Map<Integer, UUID> existing = questions.days(spaceId, ChallengeCatalogue.KEY);
        for (var day : catalogue.days()) {
            if (existing.containsKey(day.day())) continue;
            UUID topic = curriculum.topic(spaceId, day.subject(), day.topic());
            questions.create(spaceId, new QuestionDtos.CreateQuestionRequest(
                    curriculum.subject(spaceId, day.subject()), topic, "CODING",
                    day.day() < 11 ? "EASY" : "MEDIUM", day.stem(),
                    "The reference is a focused core solution or review outline. Extensions are optional. "
                            + "Rebuild the core from a blank file before calling the concept independent.",
                    day.hints(), Map.of("language", "java", "referenceSolution", day.referenceSolution(),
                            "rubric", day.checks()), 1200,
                    List.of(ChallengeCatalogue.KEY, "day-%02d".formatted(day.day()))));
        }
        return new ChallengeDtos.Enrollment(spaceId);
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.Guide guide(UUID spaceId) {
        requireChallenge(spaceId);
        var ids = questions.days(spaceId, ChallengeCatalogue.KEY);
        Map<UUID, Integer> dayById = new HashMap<>();
        ids.forEach((day, id) -> dayById.put(id, day));
        var recorded = attempts.findAllForLearner(user.requireId(), spaceId).stream()
                .filter(attempt -> dayById.containsKey(attempt.questionId())).toList();
        List<ChallengeDtos.Progress> progress = new ArrayList<>();
        for (var day : catalogue.days()) {
            var history = recorded.stream().filter(attempt -> attempt.questionId().equals(ids.get(day.day()))).toList();
            boolean completed = history.stream().anyMatch(attempt -> attempt.isCorrect()
                    && Boolean.TRUE.equals(attempt.answer().get("completed")));
            boolean independent = history.stream().anyMatch(attempt -> attempt.isCorrect()
                    && Boolean.TRUE.equals(attempt.answer().get("completed"))
                    && "INDEPENDENT".equals(attempt.answer().get("assistance")));
            String assistance = history.isEmpty() ? null
                    : String.valueOf(history.getLast().answer().getOrDefault("assistance", "UNKNOWN"));
            progress.add(new ChallengeDtos.Progress(day.day(), ids.get(day.day()), completed,
                    independent, assistance, history.size()));
        }
        var activity = recorded.stream().map(attempt -> new ChallengeDtos.Activity(
                dayById.get(attempt.questionId()), attempt.createdAt(), attempt.isCorrect()
                && Boolean.TRUE.equals(attempt.answer().get("completed")),
                String.valueOf(attempt.answer().getOrDefault("assistance", "UNKNOWN")),
                String.valueOf(attempt.answer().getOrDefault("reflection", "")))).toList();
        var briefs = catalogue.days().stream().map(day -> new ChallengeDtos.Brief(day.day(),
                day.title(), day.subject(), day.topic(), day.core(), day.stretch(), day.checks(),
                day.resourceTitle(), day.resourceUrl(), day.readingFocus(), day.revisitDay(), day.timedMinutes())).toList();
        return new ChallengeDtos.Guide(ChallengeCatalogue.KEY, briefs, progress, activity,
                sessions.inProgress(spaceId).orElse(null));
    }

    @Transactional
    public SessionDtos.SessionResponse start(UUID spaceId, int day, boolean timed) {
        requireChallenge(spaceId);
        if (day < 1 || day > 30) throw new NotFoundException("challenge.day", "Choose a day from 1 to 30.");
        UUID id = questions.days(spaceId, ChallengeCatalogue.KEY).get(day);
        if (id == null) throw new NotFoundException("challenge.question", "The exercise was archived. Reinstall the guide to restore missing exercises.");
        Integer minutes = timed ? catalogue.days().get(day - 1).timedMinutes() : null;
        return sessions.startQuestion(spaceId, id, minutes);
    }

    private void requireChallenge(UUID spaceId) {
        if (!ChallengeCatalogue.KEY.equals(guard.requireOwned(spaceId).config().get("challengeKey"))) {
            throw new NotFoundException("challenge.not_found", "This space has no Java/Spring guide.");
        }
    }
}

package com.patipp.sessions.api;

import com.patipp.sessions.api.SessionDtos.AnswerResult;
import com.patipp.sessions.api.SessionDtos.SessionAvailability;
import com.patipp.sessions.api.SessionDtos.SessionListEntry;
import com.patipp.sessions.api.SessionDtos.SessionResponse;
import com.patipp.sessions.api.SessionDtos.SessionSummary;
import com.patipp.sessions.api.SessionDtos.StartSessionRequest;
import com.patipp.sessions.api.SessionDtos.SubmitAnswerRequest;
import com.patipp.sessions.internal.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Study sessions, nested under a space because a session only exists within one.
 *
 * <p>The shape is the same for every mode. Exam mode in Phase 4 adds no endpoints; it starts
 * with {@code mode: "EXAM"} and behaves differently because of its handler, not because of a
 * different URL.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** What is available to practise under these filters, before committing to a session. */
    @PostMapping("/availability")
    public SessionAvailability availability(@PathVariable UUID spaceId,
                                            @Valid @RequestBody StartSessionRequest request) {
        return sessionService.availability(spaceId, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse start(@PathVariable UUID spaceId,
                                 @Valid @RequestBody StartSessionRequest request) {
        return sessionService.start(spaceId, request);
    }

    /** The session left open in this space, so the app can offer to resume rather than restart. */
    @GetMapping("/in-progress")
    public ResponseEntity<SessionListEntry> inProgress(@PathVariable UUID spaceId) {
        return sessionService.inProgress(spaceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    public List<SessionListEntry> history(@PathVariable UUID spaceId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return sessionService.history(spaceId, page, size);
    }

    /** The current state, including the next question to answer. */
    @GetMapping("/{sessionId}")
    public SessionResponse get(@PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessionService.get(spaceId, sessionId);
    }

    /**
     * Submits one answer.
     *
     * <p>Returns the result together with the next question, so answering and advancing is a
     * single round trip rather than two.
     */
    @PostMapping("/{sessionId}/answers")
    public AnswerResult answer(@PathVariable UUID spaceId,
                               @PathVariable UUID sessionId,
                               @Valid @RequestBody SubmitAnswerRequest request) {
        return sessionService.answer(spaceId, sessionId, request);
    }

    /**
     * Serves the question at a position. Exams only; practice is answered in order.
     */
    @GetMapping("/{sessionId}/items/{position}")
    public SessionDtos.ServedItem goTo(@PathVariable UUID spaceId,
                                       @PathVariable UUID sessionId,
                                       @PathVariable int position) {
        return sessionService.goTo(spaceId, sessionId, position);
    }

    /** Flags a question to come back to, or clears the flag. Exams only. */
    @PostMapping("/{sessionId}/items/{position}/mark")
    public SessionResponse mark(@PathVariable UUID spaceId,
                                @PathVariable UUID sessionId,
                                @PathVariable int position,
                                @RequestParam(defaultValue = "true") boolean marked) {
        return sessionService.markForReview(spaceId, sessionId, position, marked);
    }

    /** Finishes and scores the session. Safe to call more than once. */
    @PostMapping("/{sessionId}/complete")
    public SessionSummary complete(@PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessionService.complete(spaceId, sessionId);
    }

    /** Stops the session without finishing it. The answers already recorded still count. */
    @PostMapping("/{sessionId}/abandon")
    public ResponseEntity<Void> abandon(@PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        sessionService.abandon(spaceId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}/summary")
    public SessionSummary summary(@PathVariable UUID spaceId, @PathVariable UUID sessionId) {
        return sessionService.summary(spaceId, sessionId);
    }
}

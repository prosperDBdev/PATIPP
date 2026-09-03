package com.patipp.sessions.internal;

import com.patipp.common.error.BadRequestException;
import com.patipp.sessions.domain.SessionMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves a session mode to the handler that implements it.
 *
 * <p>Spring injects every {@link SessionModeHandler} on the classpath, so Phase 4 adds exam
 * mode by writing one class - nothing here or in the service changes.
 *
 * <p>Unlike the question-format hierarchy, which is sealed and resolved at compile time, this
 * is a runtime registry. The difference is that a preparation type can <em>name</em> a mode in
 * its blueprint before the code implements it, so the lookup has to be able to say "not yet"
 * at runtime rather than refusing to compile.
 */
@Component
public class SessionModeRegistry {

    private final Map<SessionMode, SessionModeHandler> handlers = new EnumMap<>(SessionMode.class);

    public SessionModeRegistry(List<SessionModeHandler> available) {
        available.forEach(handler -> handlers.put(handler.mode(), handler));
    }

    /**
     * @throws BadRequestException naming the mode, when a blueprint offers one that has not
     *                             shipped. A clear "interviews arrive in Phase 8" beats a
     *                             null pointer three frames down.
     */
    public SessionModeHandler require(SessionMode mode) {
        SessionModeHandler handler = handlers.get(mode);
        if (handler == null) {
            throw new BadRequestException("session.mode_unsupported",
                    mode + " sessions are not available yet.");
        }
        return handler;
    }

    public boolean supports(SessionMode mode) {
        return handlers.containsKey(mode);
    }
}

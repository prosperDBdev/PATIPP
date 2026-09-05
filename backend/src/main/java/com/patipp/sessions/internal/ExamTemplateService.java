package com.patipp.sessions.internal;

import com.patipp.common.error.ConflictException;
import com.patipp.common.error.NotFoundException;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.sessions.api.SessionDtos.ExamTemplateResponse;
import com.patipp.sessions.api.SessionDtos.SaveTemplateRequest;
import com.patipp.sessions.api.SessionDtos.SessionResponse;
import com.patipp.sessions.api.SessionDtos.StartSessionRequest;
import com.patipp.sessions.domain.ExamTemplate;
import com.patipp.sessions.domain.ExamTemplateRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved exam setups, and sitting one.
 *
 * <p>A template stores a request, not a paper. Sitting it draws a fresh selection from the
 * bank as it stands now, so the second sitting measures whether you have learned the material
 * rather than whether you remember last week's questions.
 */
@Service
public class ExamTemplateService {

    private final ExamTemplateRepository templates;
    private final SessionService sessionService;
    private final SpaceAccessGuard accessGuard;
    private final Clock clock;

    public ExamTemplateService(ExamTemplateRepository templates,
                               SessionService sessionService,
                               SpaceAccessGuard accessGuard,
                               Clock clock) {
        this.templates = templates;
        this.sessionService = sessionService;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ExamTemplateResponse> list(UUID spaceId) {
        accessGuard.requireOwned(spaceId);
        return templates.findLiveInSpace(spaceId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ExamTemplateResponse save(UUID spaceId, SaveTemplateRequest request) {
        accessGuard.requireWritable(spaceId);

        String name = request.name().strip();
        if (templates.existsLiveWithName(spaceId, name)) {
            throw new ConflictException("exam_template.name_taken",
                    "You already have a saved exam called \"" + name + "\".");
        }

        return toResponse(templates.save(
                ExamTemplate.create(spaceId, name, configFrom(request))));
    }

    @Transactional
    public void archive(UUID spaceId, UUID templateId) {
        accessGuard.requireWritable(spaceId);
        requireTemplate(spaceId, templateId).archive(clock.instant());
    }

    /**
     * Starts an exam from a saved setup.
     *
     * <p>The seed is deliberately not stored on the template. Replaying the identical paper
     * would turn a mock into a memory test, and the whole value of sitting one twice is that
     * the questions are different but the shape is the same.
     */
    @Transactional
    public SessionResponse sit(UUID spaceId, UUID templateId) {
        accessGuard.requireWritable(spaceId);
        ExamTemplate template = requireTemplate(spaceId, templateId);

        SessionResponse session = sessionService.start(spaceId, toStartRequest(template.config()));
        template.recordUse(clock.instant());
        return session;
    }

    // ------------------------------------------------------------------ internals

    private ExamTemplate requireTemplate(UUID spaceId, UUID templateId) {
        return templates.findInSpace(templateId, spaceId)
                .orElseThrow(() -> new NotFoundException(
                        "exam_template.not_found", "No such saved exam."));
    }

    private Map<String, Object> configFrom(SaveTemplateRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (request.length() != null) {
            config.put("length", request.length());
        }
        if (request.durationMinutes() != null) {
            config.put("durationMinutes", request.durationMinutes());
        }
        putIfPresent(config, "subjectIds", idsToStrings(request.subjectIds()));
        putIfPresent(config, "topicIds", idsToStrings(request.topicIds()));
        putIfPresent(config, "types", request.types());
        putIfPresent(config, "difficulties", request.difficulties());
        return config;
    }

    @SuppressWarnings("unchecked")
    private StartSessionRequest toStartRequest(Map<String, Object> config) {
        return new StartSessionRequest(
                "EXAM",
                readInt(config, "length"),
                readInt(config, "durationMinutes"),
                // Fresh paper every sitting.
                null,
                readIds(config, "subjectIds"),
                readIds(config, "topicIds"),
                (List<String>) config.getOrDefault("types", List.of()),
                (List<String>) config.getOrDefault("difficulties", List.of()));
    }

    private ExamTemplateResponse toResponse(ExamTemplate template) {
        Map<String, Object> config = template.config();
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) config.getOrDefault("types", List.of());
        @SuppressWarnings("unchecked")
        List<String> difficulties = (List<String>) config.getOrDefault("difficulties", List.of());

        return new ExamTemplateResponse(
                template.id(),
                template.name(),
                readInt(config, "length"),
                readInt(config, "durationMinutes"),
                readIds(config, "subjectIds"),
                types,
                difficulties,
                template.timesUsed(),
                template.lastUsedAt(),
                template.createdAt());
    }

    private static void putIfPresent(Map<String, Object> config, String key, List<?> values) {
        if (values != null && !values.isEmpty()) {
            config.put(key, values);
        }
    }

    private static List<String> idsToStrings(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().map(UUID::toString).toList();
    }

    private static Integer readInt(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(raw.toString().strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static List<UUID> readIds(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Object item : list) {
            try {
                ids.add(UUID.fromString(item.toString()));
            } catch (IllegalArgumentException notAUuid) {
                // A stale id from a deleted subject. Skipping it is right: the exam should
                // still run, just without that filter.
            }
        }
        return ids;
    }
}

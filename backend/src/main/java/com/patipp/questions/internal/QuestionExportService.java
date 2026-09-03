package com.patipp.questions.internal;

import com.patipp.curriculum.api.CurriculumLookup;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.questions.domain.Question;
import com.patipp.questions.domain.QuestionRepository;
import com.patipp.questions.domain.QuestionVersion;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports a space's live questions in the same JSON shape import accepts.
 *
 * <p>Symmetry is the requirement: whatever comes out must go back in. It means your material
 * is never trapped in this application, and it gives Phase 2 a genuinely strong test - export
 * a bank, wipe it, re-import, and assert you got the same thing back.
 *
 * <p>Subjects and topics are written by <em>name</em>, not by id. An id is meaningless in the
 * space you import into; a name is what a person wrote and what another space can match on.
 * Those names come through {@link CurriculumLookup} rather than curriculum's repositories,
 * so this module depends on the curriculum's published surface and not on how it is stored.
 */
@Service
public class QuestionExportService {

    private final QuestionRepository questions;
    private final CurriculumLookup curriculum;
    private final SpaceAccessGuard accessGuard;

    public QuestionExportService(QuestionRepository questions, CurriculumLookup curriculum,
                                 SpaceAccessGuard accessGuard) {
        this.questions = questions;
        this.curriculum = curriculum;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID spaceId) {
        var space = accessGuard.requireOwned(spaceId);

        Map<UUID, String> subjectNames = curriculum.subjectNames(spaceId);
        Map<UUID, String> topicNames = curriculum.topicNames(spaceId);

        List<Map<String, Object>> exported = questions.findAllLiveInSpace(spaceId).stream()
                .map(question -> toExportEntry(question, subjectNames, topicNames))
                .toList();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("space", space.name());
        document.put("preparationType", space.preparationType().key());
        document.put("exportedAt", Instant.now().toString());
        document.put("count", exported.size());
        document.put("questions", exported);
        return document;
    }

    private Map<String, Object> toExportEntry(Question question, Map<UUID, String> subjectNames,
                                              Map<UUID, String> topicNames) {
        QuestionVersion version = question.currentVersion();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", question.type().name());
        entry.put("difficulty", question.difficulty().name());
        putIfPresent(entry, "subject", subjectNames.get(question.subjectId()));
        putIfPresent(entry, "topic", topicNames.get(question.topicId()));
        entry.put("stem", version == null ? "" : version.stem());
        putIfPresent(entry, "explanation", version == null ? null : version.explanation());
        if (version != null && !version.hints().isEmpty()) {
            entry.put("hints", version.hints());
        }
        if (!question.tags().isEmpty()) {
            entry.put("tags", question.tags());
        }
        entry.put("estimatedSeconds", question.estimatedSeconds());
        entry.put("payload", version == null ? Map.of() : version.payload());
        return entry;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}

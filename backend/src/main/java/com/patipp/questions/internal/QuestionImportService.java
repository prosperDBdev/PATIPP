package com.patipp.questions.internal;

import com.patipp.common.error.BadRequestException;
import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.questions.api.QuestionDtos.FieldProblem;
import com.patipp.questions.api.QuestionDtos.ImportReport;
import com.patipp.questions.api.QuestionDtos.ImportRequest;
import com.patipp.questions.api.QuestionDtos.ImportRow;
import com.patipp.questions.domain.ContentHash;
import com.patipp.questions.domain.Question;
import com.patipp.questions.domain.QuestionRepository;
import com.patipp.questions.domain.QuestionSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk import, in two passes: understand the whole file, then write it.
 *
 * <p>The dry run is the default and the point. An import that fails halfway leaves a bank in
 * a state nobody asked for, and an import that silently skips bad rows leaves you believing
 * you have fifty questions when you have forty-one. Parsing everything first means the report
 * you approve is the outcome you get.
 */
@Service
public class QuestionImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImportService.class);
    private static final int MAX_ROWS = 2000;

    static final String OUTCOME_VALID = "VALID";
    static final String OUTCOME_IMPORTED = "IMPORTED";
    static final String OUTCOME_DUPLICATE = "DUPLICATE";
    static final String OUTCOME_INVALID = "INVALID";

    private final QuestionRepository questions;
    private final QuestionService questionService;
    private final CsvQuestionParser csvParser;
    private final JsonQuestionParser jsonParser;
    private final SpaceAccessGuard accessGuard;
    private final CurrentUser currentUser;

    public QuestionImportService(QuestionRepository questions,
                                 QuestionService questionService,
                                 CsvQuestionParser csvParser,
                                 JsonQuestionParser jsonParser,
                                 SpaceAccessGuard accessGuard,
                                 CurrentUser currentUser) {
        this.questions = questions;
        this.questionService = questionService;
        this.csvParser = csvParser;
        this.jsonParser = jsonParser;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
    }

    @Transactional
    public ImportReport importQuestions(UUID spaceId, ImportRequest request) {
        accessGuard.requireWritable(spaceId);

        List<ParsedQuestion> parsed = parse(spaceId, request);
        if (parsed.size() > MAX_ROWS) {
            throw new BadRequestException("import.too_large",
                    "An import may contain at most " + MAX_ROWS + " questions; this has "
                            + parsed.size() + ".");
        }

        // Hashes of everything already in the space that a valid row would collide with.
        Set<String> candidateHashes = new HashSet<>();
        Map<Integer, String> hashByLine = new LinkedHashMap<>();
        for (ParsedQuestion row : parsed) {
            if (row.isValid()) {
                String hash = ContentHash.of(row.type(), row.stem(), row.content());
                hashByLine.put(row.line(), hash);
                candidateHashes.add(hash);
            }
        }

        Set<String> existingHashes = candidateHashes.isEmpty()
                ? Set.of()
                : questions.findLiveByHashes(spaceId, candidateHashes).stream()
                        .map(Question::contentHash)
                        .collect(java.util.stream.Collectors.toSet());

        // Tracks duplicates *within the file itself*, which is just as common as colliding
        // with the existing bank and would otherwise slip through the check above.
        Set<String> seenInThisFile = new HashSet<>();

        List<ImportRow> rows = new ArrayList<>();
        int valid = 0;
        int duplicates = 0;
        int invalid = 0;
        int imported = 0;

        for (ParsedQuestion row : parsed) {
            if (!row.isValid()) {
                invalid++;
                rows.add(new ImportRow(row.line(), OUTCOME_INVALID, row.stem(), row.typeLabel(),
                        row.problems(), null));
                continue;
            }

            String hash = hashByLine.get(row.line());
            if (existingHashes.contains(hash) || !seenInThisFile.add(hash)) {
                duplicates++;
                rows.add(new ImportRow(row.line(), OUTCOME_DUPLICATE, row.stem(), row.typeLabel(),
                        List.of(new FieldProblem("stem", "already exists in this space")), null));
                continue;
            }

            valid++;

            if (request.isDryRun()) {
                rows.add(new ImportRow(row.line(), OUTCOME_VALID, row.stem(), row.typeLabel(),
                        List.of(), null));
                continue;
            }

            Question question = Question.create(
                    spaceId, row.subjectId(), row.topicId(), row.type(), row.difficulty(),
                    QuestionSource.IMPORT, "import:" + request.format().toLowerCase(Locale.ROOT),
                    row.estimatedSeconds(), row.tags(), hash, currentUser.requireId());

            questionService.persistWithFirstVersion(
                    question, row.stem(), row.explanation(), row.hints(), row.content());

            imported++;
            rows.add(new ImportRow(row.line(), OUTCOME_IMPORTED, row.stem(), row.typeLabel(),
                    List.of(), question.id()));
        }

        if (!request.isDryRun()) {
            log.info("Imported {} of {} rows into space {} ({} duplicates, {} invalid)",
                    imported, parsed.size(), spaceId, duplicates, invalid);
        }

        return new ImportReport(request.isDryRun(), parsed.size(), valid, duplicates, invalid,
                imported, rows);
    }

    private List<ParsedQuestion> parse(UUID spaceId, ImportRequest request) {
        String format = request.format().strip().toUpperCase(Locale.ROOT);
        return switch (format) {
            case "CSV" -> csvParser.parse(request.content(), spaceId,
                    request.defaultSubjectId(), request.defaultTopicId());
            case "JSON" -> jsonParser.parse(request.content(), spaceId,
                    request.defaultSubjectId(), request.defaultTopicId());
            default -> throw new BadRequestException("import.format_invalid",
                    "Format must be CSV or JSON.");
        };
    }
}

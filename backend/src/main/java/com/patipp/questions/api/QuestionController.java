package com.patipp.questions.api;

import com.patipp.questions.api.QuestionDtos.CreateQuestionRequest;
import com.patipp.questions.api.QuestionDtos.ImportReport;
import com.patipp.questions.api.QuestionDtos.ImportRequest;
import com.patipp.questions.api.QuestionDtos.QuestionPage;
import com.patipp.questions.api.QuestionDtos.QuestionResponse;
import com.patipp.questions.api.QuestionDtos.UpdateQuestionRequest;
import com.patipp.questions.api.QuestionDtos.VersionSummary;
import com.patipp.questions.internal.QuestionExportService;
import com.patipp.questions.internal.QuestionImportService;
import com.patipp.questions.internal.QuestionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question bank endpoints, nested under a space because a question exists only within one.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/questions")
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionImportService importService;
    private final QuestionExportService exportService;

    public QuestionController(QuestionService questionService,
                              QuestionImportService importService,
                              QuestionExportService exportService) {
        this.questionService = questionService;
        this.importService = importService;
        this.exportService = exportService;
    }

    @GetMapping
    public QuestionPage list(@PathVariable UUID spaceId,
                             @RequestParam(required = false) UUID subjectId,
                             @RequestParam(required = false) UUID topicId,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String difficulty,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String search,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        return questionService.search(spaceId, subjectId, topicId, type, difficulty, status,
                search, page, size);
    }

    @GetMapping("/{questionId}")
    public QuestionResponse get(@PathVariable UUID spaceId, @PathVariable UUID questionId) {
        return questionService.get(spaceId, questionId);
    }

    /** The full edit history. Attempts pin one of these rows, so nothing here is ever lost. */
    @GetMapping("/{questionId}/versions")
    public List<VersionSummary> versions(@PathVariable UUID spaceId,
                                         @PathVariable UUID questionId) {
        return questionService.history(spaceId, questionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionResponse create(@PathVariable UUID spaceId,
                                   @Valid @RequestBody CreateQuestionRequest request) {
        return questionService.create(spaceId, request);
    }

    @PatchMapping("/{questionId}")
    public QuestionResponse update(@PathVariable UUID spaceId,
                                   @PathVariable UUID questionId,
                                   @Valid @RequestBody UpdateQuestionRequest request) {
        return questionService.update(spaceId, questionId, request);
    }

    /** Archives the question. Soft, because attempts will reference it from Phase 3. */
    @DeleteMapping("/{questionId}")
    public ResponseEntity<Void> archive(@PathVariable UUID spaceId,
                                        @PathVariable UUID questionId) {
        questionService.archive(spaceId, questionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{questionId}/restore")
    public QuestionResponse restore(@PathVariable UUID spaceId, @PathVariable UUID questionId) {
        return questionService.restore(spaceId, questionId);
    }

    /**
     * Imports a CSV or JSON file.
     *
     * <p>Defaults to a dry run: the response tells you exactly what would happen, row by row,
     * and nothing is written until the same request is sent again with {@code dryRun: false}.
     */
    @PostMapping("/import")
    public ImportReport importQuestions(@PathVariable UUID spaceId,
                                        @Valid @RequestBody ImportRequest request) {
        return importService.importQuestions(spaceId, request);
    }

    /** Exports the live bank in the shape import accepts, so a bank is never trapped here. */
    @GetMapping("/export")
    public Map<String, Object> export(@PathVariable UUID spaceId) {
        return exportService.export(spaceId);
    }
}

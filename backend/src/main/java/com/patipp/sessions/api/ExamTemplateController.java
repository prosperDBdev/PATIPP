package com.patipp.sessions.api;

import com.patipp.sessions.api.SessionDtos.ExamTemplateResponse;
import com.patipp.sessions.api.SessionDtos.SaveTemplateRequest;
import com.patipp.sessions.api.SessionDtos.SessionResponse;
import com.patipp.sessions.internal.ExamTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved exam setups.
 *
 * <p>Sitting one returns an ordinary session, so the runner that already exists handles it
 * with no knowledge that a template was involved.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/exam-templates")
public class ExamTemplateController {

    private final ExamTemplateService templateService;

    public ExamTemplateController(ExamTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<ExamTemplateResponse> list(@PathVariable UUID spaceId) {
        return templateService.list(spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExamTemplateResponse save(@PathVariable UUID spaceId,
                                     @Valid @RequestBody SaveTemplateRequest request) {
        return templateService.save(spaceId, request);
    }

    /** Starts an exam from this setup. The paper is drawn fresh, not replayed. */
    @PostMapping("/{templateId}/sit")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse sit(@PathVariable UUID spaceId, @PathVariable UUID templateId) {
        return templateService.sit(spaceId, templateId);
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> archive(@PathVariable UUID spaceId,
                                        @PathVariable UUID templateId) {
        templateService.archive(spaceId, templateId);
        return ResponseEntity.noContent().build();
    }
}

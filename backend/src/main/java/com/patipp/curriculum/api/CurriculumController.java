package com.patipp.curriculum.api;

import com.patipp.curriculum.api.CurriculumDtos.CreateSubjectRequest;
import com.patipp.curriculum.api.CurriculumDtos.CreateTopicRequest;
import com.patipp.curriculum.api.CurriculumDtos.SubjectResponse;
import com.patipp.curriculum.api.CurriculumDtos.TopicResponse;
import com.patipp.curriculum.api.CurriculumDtos.UpdateSubjectRequest;
import com.patipp.curriculum.api.CurriculumDtos.UpdateTopicRequest;
import com.patipp.curriculum.internal.CurriculumService;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curriculum endpoints, all nested under a space.
 *
 * <p>The space id is in the path rather than in a header or a body field because it is the
 * partition key: every one of these resources exists only within a space, and a URL that
 * did not say which space would be describing something that does not exist.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class CurriculumController {

    private final CurriculumService curriculumService;

    public CurriculumController(CurriculumService curriculumService) {
        this.curriculumService = curriculumService;
    }

    /** Subjects with their topic trees already assembled - one call for the whole editor. */
    @GetMapping("/curriculum")
    public List<SubjectResponse> curriculum(@PathVariable UUID spaceId) {
        return curriculumService.curriculum(spaceId);
    }

    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    public SubjectResponse createSubject(@PathVariable UUID spaceId,
                                         @Valid @RequestBody CreateSubjectRequest request) {
        return curriculumService.createSubject(spaceId, request);
    }

    @PatchMapping("/subjects/{subjectId}")
    public SubjectResponse updateSubject(@PathVariable UUID spaceId,
                                         @PathVariable UUID subjectId,
                                         @Valid @RequestBody UpdateSubjectRequest request) {
        return curriculumService.updateSubject(spaceId, subjectId, request);
    }

    @DeleteMapping("/subjects/{subjectId}")
    public ResponseEntity<Void> archiveSubject(@PathVariable UUID spaceId,
                                               @PathVariable UUID subjectId) {
        curriculumService.archiveSubject(spaceId, subjectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicResponse createTopic(@PathVariable UUID spaceId,
                                     @Valid @RequestBody CreateTopicRequest request) {
        return curriculumService.createTopic(spaceId, request);
    }

    @PatchMapping("/topics/{topicId}")
    public TopicResponse updateTopic(@PathVariable UUID spaceId,
                                     @PathVariable UUID topicId,
                                     @Valid @RequestBody UpdateTopicRequest request) {
        return curriculumService.updateTopic(spaceId, topicId, request);
    }

    @DeleteMapping("/topics/{topicId}")
    public ResponseEntity<Void> archiveTopic(@PathVariable UUID spaceId,
                                             @PathVariable UUID topicId) {
        curriculumService.archiveTopic(spaceId, topicId);
        return ResponseEntity.noContent().build();
    }
}

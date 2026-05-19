package diplom.platform.ui;

import diplom.platform.application.AssignmentService;
import diplom.platform.application.AssignmentMaterialService;
import diplom.platform.application.AssignmentTestSystemService;
import diplom.platform.application.ReviewService;
import diplom.platform.evaluation.infrastructure.AIReviewEntity;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.AssignmentDto;
import diplom.platform.ui.dto.AssignmentMaterialDto;
import diplom.platform.ui.dto.AssignmentSubmissionRosterRowDto;
import diplom.platform.ui.dto.AssignmentTestDto;
import diplom.platform.ui.dto.AssignmentTestQuestionDto;
import diplom.platform.ui.dto.SubmitLabResponseDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {
    private final LearningPlatformFacade facade;
    private final ReviewService reviewService;
    private final AssignmentService assignmentService;
    private final AssignmentMaterialService assignmentMaterialService;
    private final AssignmentTestSystemService assignmentTestSystemService;

    public AssignmentController(LearningPlatformFacade facade, ReviewService reviewService, AssignmentService assignmentService,
                                AssignmentMaterialService assignmentMaterialService, AssignmentTestSystemService assignmentTestSystemService) {
        this.facade = facade;
        this.reviewService = reviewService;
        this.assignmentService = assignmentService;
        this.assignmentMaterialService = assignmentMaterialService;
        this.assignmentTestSystemService = assignmentTestSystemService;
    }

    @GetMapping("/{assignmentId}")
    @PreAuthorize("isAuthenticated()")
    public AssignmentDto getAssignment(@PathVariable UUID assignmentId) {
        var a = assignmentService.getAssignment(assignmentId);
        return new AssignmentDto(
                a.getId(),
                a.getCourse().getId(),
                a.getTopicTitle(),
                a.getTopicDescription(),
                a.getSubjectArea(),
                a.getDueDate(),
                a.isAllowLateSubmission(),
                a.getLatePenaltyPercent(),
                a.getMaxScore()
        );
    }

    @GetMapping("/{assignmentId}/submissions")
    @PreAuthorize("hasRole('TEACHER')")
    public List<AssignmentSubmissionRosterRowDto> submissions(@PathVariable UUID assignmentId) {
        return assignmentService.listSubmissionRoster(assignmentId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/{assignmentId}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmitLabResponseDto submitWork(@PathVariable UUID assignmentId, @RequestBody SubmitLabRequest request) {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        return facade.submitAssignment(assignmentId, me.id(), request.textContent(), request.attachmentKey(), request.language());
    }

    @PostMapping("/labworks/{labWorkId}/review")
    @PreAuthorize("hasRole('TEACHER')")
    public AIReviewEntity triggerReview(@PathVariable UUID labWorkId) {
        return reviewService.generateReview(labWorkId, SecurityUtils.requireCurrentUser());
    }

    @DeleteMapping("/labworks/{labWorkId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public void deleteLabWork(@PathVariable UUID labWorkId) {
        assignmentService.deleteLabWork(labWorkId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/{assignmentId}/materials/upload")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentMaterialDto uploadMaterial(@PathVariable UUID assignmentId, @RequestParam("file") MultipartFile file) {
        return assignmentMaterialService.upload(assignmentId, file);
    }

    @GetMapping("/{assignmentId}/materials")
    @PreAuthorize("isAuthenticated()")
    public List<AssignmentMaterialDto> listMaterials(@PathVariable UUID assignmentId) {
        return assignmentMaterialService.list(assignmentId);
    }

    @PostMapping("/{assignmentId}/test/generate-preview")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentTestDto generateTestPreview(@PathVariable UUID assignmentId, @RequestBody GenerateTestPreviewRequest request) {
        return assignmentTestSystemService.generatePreview(assignmentId, request.questionCount(), request.notes());
    }

    @PostMapping("/test/generate-preview-from-draft")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentTestDto generateTestPreviewFromDraft(@RequestBody GenerateTestPreviewFromDraftRequest request) {
        return assignmentTestSystemService.generatePreviewFromDraft(
                request.topicTitle(),
                request.topicDescription(),
                request.subjectArea(),
                request.questionCount(),
                request.notes()
        );
    }

    @PostMapping("/{assignmentId}/test")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentTestDto upsertTest(@PathVariable UUID assignmentId, @RequestBody UpsertTestRequest request) {
        return assignmentTestSystemService.upsert(assignmentId, request.title(), request.maxScore(), request.questions());
    }

    @GetMapping("/{assignmentId}/test")
    @PreAuthorize("isAuthenticated()")
    public AssignmentTestDto getTest(@PathVariable UUID assignmentId) {
        return assignmentTestSystemService.get(assignmentId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/{assignmentId}/test/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public AssignmentTestSystemService.TestSubmitResult submitAssignmentTest(@PathVariable UUID assignmentId, @RequestBody Map<UUID, String> answers) {
        return assignmentTestSystemService.submit(assignmentId, SecurityUtils.requireCurrentUser(), answers);
    }

    @GetMapping("/{assignmentId}/test/my-status")
    @PreAuthorize("hasRole('STUDENT')")
    public AssignmentTestSystemService.TestAttemptStatus myAssignmentTestStatus(@PathVariable UUID assignmentId) {
        return assignmentTestSystemService.myAttemptStatus(assignmentId, SecurityUtils.requireCurrentUser());
    }

    public record SubmitLabRequest(String textContent, String attachmentKey, String language) {
    }

    public record UpsertTestRequest(String title, double maxScore, List<AssignmentTestQuestionDto> questions) {
    }

    public record GenerateTestPreviewRequest(int questionCount, String notes) {
    }

    public record GenerateTestPreviewFromDraftRequest(
            String topicTitle,
            String topicDescription,
            String subjectArea,
            int questionCount,
            String notes
    ) {
    }
}

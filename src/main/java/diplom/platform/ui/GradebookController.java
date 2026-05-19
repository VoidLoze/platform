package diplom.platform.ui;

import diplom.platform.application.GradebookService;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.GradebookCourseDto;
import diplom.platform.ui.dto.GradebookEntryDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gradebook")
public class GradebookController {
    private final GradebookService gradebookService;

    public GradebookController(GradebookService gradebookService) {
        this.gradebookService = gradebookService;
    }

    @GetMapping("/courses/{courseId}")
    @PreAuthorize("hasRole('TEACHER')")
    public GradebookCourseDto journal(@PathVariable UUID courseId) {
        return gradebookService.getCourseJournal(courseId, SecurityUtils.requireCurrentUser());
    }

    @PatchMapping("/labworks/{labWorkId}/grade")
    @PreAuthorize("hasRole('TEACHER')")
    public GradebookEntryDto grade(@PathVariable UUID labWorkId, @RequestBody SetGradeRequest request) {
        return gradebookService.setManualGrade(labWorkId, request.gradeValue(), SecurityUtils.requireCurrentUser());
    }

    public record SetGradeRequest(double gradeValue) {}
}

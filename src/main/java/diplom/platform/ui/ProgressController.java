package diplom.platform.ui;

import diplom.platform.application.ProgressService;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.CourseProgressDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController {
    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/teacher")
    @PreAuthorize("hasRole('TEACHER')")
    public List<CourseProgressDto> teacherProgress() {
        return progressService.getTeacherProgress(SecurityUtils.requireCurrentUser().id());
    }

    @GetMapping("/courses/{courseId}")
    @PreAuthorize("hasRole('TEACHER')")
    public CourseProgressDto courseProgress(@PathVariable UUID courseId) {
        return progressService.getCourseProgress(SecurityUtils.requireCurrentUser().id(), courseId);
    }
}

package diplom.platform.ui;

import diplom.platform.application.AssignmentService;
import diplom.platform.application.CourseService;
import diplom.platform.application.UserService;
import diplom.platform.evaluation.domain.AIService;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.ui.dto.AssignmentDto;
import diplom.platform.ui.dto.CourseDto;
import diplom.platform.ui.dto.UserProfileDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {
    private final CourseService courseService;
    private final AssignmentService assignmentService;
    private final UserService userService;

    public CourseController(CourseService courseService, AssignmentService assignmentService, UserService userService) {
        this.courseService = courseService;
        this.assignmentService = assignmentService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CourseDto> listCourses() {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        if (me.role() == UserRole.ROLE_STUDENT) {
            return courseService.listEnrolledForStudent(me.id()).stream().map(CourseController::toCourseDto).toList();
        }
        if (me.role() == UserRole.ROLE_TEACHER) {
            return courseService.listMyCourses(me.id()).stream().map(CourseController::toCourseDto).toList();
        }
        return courseService.listCourses().stream().map(CourseController::toCourseDto).toList();
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('TEACHER')")
    public List<CourseDto> myCourses() {
        UUID ownerId = SecurityUtils.requireCurrentUser().id();
        return courseService.listMyCourses(ownerId).stream().map(CourseController::toCourseDto).toList();
    }

    @GetMapping("/{courseId}")
    @PreAuthorize("isAuthenticated()")
    public CourseDto getCourse(@PathVariable UUID courseId) {
        return toCourseDto(courseService.getCourseForViewer(courseId, SecurityUtils.requireCurrentUser()));
    }

    @GetMapping("/{courseId}/members")
    @PreAuthorize("hasRole('TEACHER')")
    public List<UserProfileDto> groupMembers(@PathVariable UUID courseId) {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        return courseService.getGroupRoster(courseId, me).stream()
                .map(CourseController::userToDto)
                .toList();
    }

    @GetMapping("/{courseId}/assignments")
    @PreAuthorize("isAuthenticated()")
    public List<AssignmentDto> listAssignments(@PathVariable UUID courseId) {
        courseService.assertCanAccessCourse(courseId, SecurityUtils.requireCurrentUser());
        return assignmentService.listAssignmentsForCourse(courseId).stream().map(CourseController::toAssignmentDto).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public CourseDto createCourse(@RequestBody CreateCourseRequest request) {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        UserEntity owner = userService.findById(me.id());
        return toCourseDto(courseService.createCourse(request.title(), owner));
    }

    @PostMapping("/{courseId}/enroll/{studentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public CourseDto enrollStudent(@PathVariable UUID courseId, @PathVariable UUID studentId) {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        return toCourseDto(courseService.enrollStudent(courseId, studentId, me.id()));
    }

    @PostMapping("/{courseId}/assignments")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentDto createAssignment(@PathVariable UUID courseId, @RequestBody CreateAssignmentRequest request) {
        courseService.assertTeacherOwnsOrAdmin(courseId, SecurityUtils.requireCurrentUser());
        AssignmentEntity a = assignmentService.createAssignment(courseId, request.topicTitle(), request.topicDescription(), request.subjectArea(),
                request.dueDate(), request.allowLateSubmission(), request.latePenaltyPercent(), request.maxScore());
        return toAssignmentDto(a);
    }

    @DeleteMapping("/{courseId}/assignments/{assignmentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public void deleteAssignment(@PathVariable UUID courseId, @PathVariable UUID assignmentId) {
        courseService.assertTeacherOwnsOrAdmin(courseId, SecurityUtils.requireCurrentUser());
        assignmentService.deleteAssignment(assignmentId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/{courseId}/assignments/generate")
    @PreAuthorize("hasRole('TEACHER')")
    public GeneratedAssignmentDraftDto generateAssignmentDraft(@PathVariable UUID courseId, @RequestBody GenerateAssignmentDraftRequest request) {
        courseService.assertTeacherOwnsOrAdmin(courseId, SecurityUtils.requireCurrentUser());
        AIService.GeneratedAssignmentDraft draft = assignmentService.generateDraft(request.subjectArea(), request.difficulty(), request.learningGoals());
        return new GeneratedAssignmentDraftDto(
                draft.topicTitle(),
                draft.topicDescription(),
                draft.subjectArea(),
                draft.recommendedMaxScore(),
                draft.recommendedLatePenaltyPercent()
        );
    }

    private static UserProfileDto userToDto(UserEntity u) {
        return new UserProfileDto(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getMiddleName(),
                u.getEmail(),
                u.getRole(),
                u.getAvatarFileKey(),
                u.getBio());
    }

    private static CourseDto toCourseDto(CourseEntity c) {
        UUID ownerId = c.getOwner() == null ? null : c.getOwner().getId();
        return new CourseDto(
                c.getId(),
                c.getTitle(),
                ownerId,
                c.getStudents().size(),
                c.getAvatarFileKey(),
                c.getDescription());
    }

    private static AssignmentDto toAssignmentDto(AssignmentEntity a) {
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

    public record CreateCourseRequest(String title) {
    }

    public record CreateAssignmentRequest(String topicTitle, String topicDescription, String subjectArea,
                                          OffsetDateTime dueDate, boolean allowLateSubmission, double latePenaltyPercent, double maxScore) {
    }

    public record GenerateAssignmentDraftRequest(String subjectArea, String difficulty, String learningGoals) {
    }

    public record GeneratedAssignmentDraftDto(
            String topicTitle,
            String topicDescription,
            String subjectArea,
            double recommendedMaxScore,
            double recommendedLatePenaltyPercent
    ) {
    }
}

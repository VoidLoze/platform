package diplom.platform.ui;

import diplom.platform.application.AssignmentService;
import diplom.platform.application.CourseService;
import diplom.platform.application.UserService;
import diplom.platform.ui.dto.SubmitLabResponseDto;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import diplom.platform.learning.infrastructure.CourseEntity;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class LearningPlatformFacade {
    private final UserService userService;
    private final CourseService courseService;
    private final AssignmentService assignmentService;

    public LearningPlatformFacade(UserService userService, CourseService courseService, AssignmentService assignmentService) {
        this.userService = userService;
        this.courseService = courseService;
        this.assignmentService = assignmentService;
    }

    public UserEntity registerUser(String firstName, String lastName, String middleName, String email, String password, UserRole role) {
        return userService.registerUser(firstName, lastName, middleName, email, password, role, null);
    }

    public CourseEntity createCourse(String title, UUID ownerId) {
        UserEntity owner = userService.findById(ownerId);
        return courseService.createCourse(title, owner);
    }

    public AssignmentEntity createAssignment(UUID courseId, String topicTitle, String topicDescription, String subjectArea,
                                             OffsetDateTime dueDate, boolean allowLate, double latePenalty, double maxScore) {
        return assignmentService.createAssignment(courseId, topicTitle, topicDescription, subjectArea, dueDate, allowLate, latePenalty, maxScore);
    }

    public SubmitLabResponseDto submitAssignment(UUID assignmentId, UUID studentId, String textContent, String attachmentKey, String language) {
        return assignmentService.submitAssignment(assignmentId, studentId, textContent, attachmentKey, language);
    }
}

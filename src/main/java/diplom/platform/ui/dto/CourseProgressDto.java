package diplom.platform.ui.dto;

import java.util.List;
import java.util.UUID;

public record CourseProgressDto(
        UUID courseId,
        String courseTitle,
        long assignmentsCount,
        long submissionsCount,
        double averageGrade,
        List<StudentProgressDto> students
) {
}

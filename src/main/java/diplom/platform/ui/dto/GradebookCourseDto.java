package diplom.platform.ui.dto;

import java.util.List;
import java.util.UUID;

public record GradebookCourseDto(
        UUID courseId,
        String courseTitle,
        List<GradebookEntryDto> entries
) {
}

package diplom.platform.ui.dto;

import diplom.platform.learning.domain.CalendarTaskStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CalendarTaskDto(
        UUID id,
        UUID assignmentId,
        UUID courseId,
        String courseTitle,
        String courseAvatarFileKey,
        String title,
        OffsetDateTime dueDate,
        CalendarTaskStatus status
) {
}

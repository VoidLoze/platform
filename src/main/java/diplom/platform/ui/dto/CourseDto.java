package diplom.platform.ui.dto;

import java.util.UUID;

public record CourseDto(
        UUID id,
        String title,
        UUID ownerId,
        int enrolledCount,
        String avatarFileKey,
        String description) {
}

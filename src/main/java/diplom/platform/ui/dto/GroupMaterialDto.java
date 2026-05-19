package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GroupMaterialDto(
        UUID id,
        UUID courseId,
        UUID uploaderId,
        String uploaderName,
        String fileKey,
        String originalName,
        String contentType,
        Long sizeBytes,
        String title,
        String description,
        OffsetDateTime uploadedAt
) {
}

package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignmentMaterialDto(
        UUID id,
        String fileKey,
        String originalName,
        String contentType,
        OffsetDateTime uploadedAt
) {
}

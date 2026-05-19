package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record GroupPostDto(
        UUID id,
        UUID courseId,
        UUID authorId,
        String authorName,
        String title,
        String body,
        boolean pinned,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<GroupPostAttachmentDto> attachments
) {
    public record GroupPostAttachmentDto(
            UUID id,
            String fileKey,
            String originalName,
            String contentType,
            Long sizeBytes
    ) {
    }
}

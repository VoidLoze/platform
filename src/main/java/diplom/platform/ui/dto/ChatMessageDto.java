package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ChatMessageDto(
        UUID id,
        UUID roomId,
        UUID senderId,
        String senderName,
        String content,
        OffsetDateTime createdAt,
        List<ChatAttachmentDto> attachments
) {
    public ChatMessageDto {
        attachments = attachments == null ? List.of() : attachments;
    }
}

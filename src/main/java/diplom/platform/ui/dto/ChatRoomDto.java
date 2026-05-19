package diplom.platform.ui.dto;

import diplom.platform.communication.domain.ChatRoomType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatRoomDto(
        UUID id,
        ChatRoomType roomType,
        String title,
        String counterpartAvatarFileKey,
        UUID courseId,
        OffsetDateTime createdAt,
        long unreadCount
) {
}

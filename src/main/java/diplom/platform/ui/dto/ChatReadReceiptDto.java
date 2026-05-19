package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatReadReceiptDto(
        UUID userId,
        String userName,
        UUID messageId,
        OffsetDateTime readAt
) {
}

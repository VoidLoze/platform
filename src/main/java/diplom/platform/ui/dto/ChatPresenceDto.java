package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatPresenceDto(
        UUID roomId,
        List<UUID> onlineUserIds,
        Map<UUID, OffsetDateTime> lastSeenByUser
) {
}

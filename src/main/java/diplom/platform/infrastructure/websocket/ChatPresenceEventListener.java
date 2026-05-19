package diplom.platform.infrastructure.websocket;

import diplom.platform.application.ChatPresenceService;
import diplom.platform.infrastructure.events.ChatRealtimeGateway;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.ui.dto.ChatPresenceDto;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.UUID;

@Component
public class ChatPresenceEventListener {
    private final ChatPresenceService presenceService;
    private final ChatRealtimeGateway realtimeGateway;

    public ChatPresenceEventListener(ChatPresenceService presenceService, ChatRealtimeGateway realtimeGateway) {
        this.presenceService = presenceService;
        this.realtimeGateway = realtimeGateway;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/rooms/")) {
            return;
        }
        String[] parts = destination.split("/");
        if (parts.length < 4) {
            return;
        }
        String roomPart = parts[3];
        try {
            UUID roomId = UUID.fromString(roomPart);
            Object principal = accessor.getSessionAttributes() != null ? accessor.getSessionAttributes().get("platformUser") : null;
            if (principal instanceof PlatformUser user && accessor.getSessionId() != null) {
                presenceService.subscribe(accessor.getSessionId(), roomId, user.id());
                realtimeGateway.broadcastPresence(new ChatPresenceDto(
                        roomId,
                        presenceService.getOnlineUsers(roomId),
                        presenceService.getLastSeenByUser(roomId)
                ));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null) {
            UUID roomId = presenceService.disconnect(event.getSessionId());
            if (roomId != null) {
                realtimeGateway.broadcastPresence(new ChatPresenceDto(
                        roomId,
                        presenceService.getOnlineUsers(roomId),
                        presenceService.getLastSeenByUser(roomId)
                ));
            }
        }
    }
}

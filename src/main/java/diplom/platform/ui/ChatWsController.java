package diplom.platform.ui;

import diplom.platform.application.ChatService;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.ui.dto.ChatAttachmentRequestDto;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
public class ChatWsController {
    private final ChatService chatService;

    public ChatWsController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/chat.send")
    public void send(@Payload ChatSendPayload payload, SimpMessageHeaderAccessor accessor) {
        Object principal = accessor.getSessionAttributes() != null ? accessor.getSessionAttributes().get("platformUser") : null;
        if (!(principal instanceof PlatformUser user)) {
            throw new org.springframework.security.access.AccessDeniedException("Unauthorized websocket request");
        }
        List<ChatAttachmentRequestDto> atts =
                payload.attachments() != null ? payload.attachments() : List.of();
        chatService.sendMessage(payload.roomId(), user.id(), payload.content(), atts);
    }

    public record ChatSendPayload(UUID roomId, String content, List<ChatAttachmentRequestDto> attachments) {}
}

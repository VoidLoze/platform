package diplom.platform.ui;

import diplom.platform.application.ChatService;
import diplom.platform.application.ChatPresenceService;
import diplom.platform.infrastructure.security.SecurityUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import diplom.platform.ui.dto.ChatAttachmentRequestDto;
import diplom.platform.ui.dto.ChatMessageDto;
import diplom.platform.ui.dto.ChatPresenceDto;
import diplom.platform.ui.dto.ChatReadReceiptDto;
import diplom.platform.ui.dto.ChatRoomDto;
import diplom.platform.ui.dto.UserProfileDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatPresenceService chatPresenceService;

    public ChatController(ChatService chatService, ChatPresenceService chatPresenceService) {
        this.chatService = chatService;
        this.chatPresenceService = chatPresenceService;
    }

    @GetMapping("/rooms")
    @PreAuthorize("isAuthenticated()")
    public List<ChatRoomDto> myRooms() {
        return chatService.listRooms(SecurityUtils.requireCurrentUser().id());
    }

    @PostMapping("/rooms/direct")
    @PreAuthorize("isAuthenticated()")
    public ChatRoomDto createDirect(@RequestBody DirectRoomRequest request) {
        return chatService.createDirectRoom(SecurityUtils.requireCurrentUser().id(), request.secondUserId());
    }

    @PostMapping("/rooms/group")
    @PreAuthorize("isAuthenticated()")
    public ChatRoomDto createGroup(@RequestBody GroupRoomRequest request) {
        return chatService.createGroupRoom(SecurityUtils.requireCurrentUser().id(), request.title(), request.participantIds());
    }

    @PostMapping("/rooms/course")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ChatRoomDto createCourse(@RequestBody CourseRoomRequest request) {
        return chatService.createCourseRoom(SecurityUtils.requireCurrentUser().id(), request.courseId());
    }

    @GetMapping("/rooms/{roomId}/messages")
    @PreAuthorize("isAuthenticated()")
    public List<ChatMessageDto> roomHistory(@PathVariable UUID roomId) {
        return chatService.roomHistory(roomId, SecurityUtils.requireCurrentUser().id());
    }

    @PostMapping("/rooms/{roomId}/messages")
    @PreAuthorize("isAuthenticated()")
    public ChatMessageDto send(@PathVariable UUID roomId, @RequestBody SendMessageRequest request) {
        return chatService.sendMessage(roomId, SecurityUtils.requireCurrentUser().id(), request.content(), request.attachments());
    }

    @DeleteMapping("/rooms/{roomId}")
    @PreAuthorize("isAuthenticated()")
    public void deleteRoom(@PathVariable UUID roomId) {
        chatService.deleteRoom(roomId, SecurityUtils.requireCurrentUser().id());
    }

    @PostMapping("/rooms/{roomId}/read")
    @PreAuthorize("isAuthenticated()")
    public ChatReadReceiptDto markRead(@PathVariable UUID roomId, @RequestBody MarkReadRequest request) {
        return chatService.markRead(roomId, SecurityUtils.requireCurrentUser().id(), request.messageId());
    }

    @GetMapping("/rooms/{roomId}/receipts")
    @PreAuthorize("isAuthenticated()")
    public List<ChatReadReceiptDto> receipts(@PathVariable UUID roomId) {
        return chatService.readReceipts(roomId, SecurityUtils.requireCurrentUser().id());
    }

    @GetMapping("/rooms/{roomId}/presence")
    @PreAuthorize("isAuthenticated()")
    public ChatPresenceDto presence(@PathVariable UUID roomId) {
        if (!chatService.isParticipant(roomId, SecurityUtils.requireCurrentUser().id())) {
            throw new org.springframework.security.access.AccessDeniedException("Forbidden");
        }
        return new ChatPresenceDto(roomId, chatPresenceService.getOnlineUsers(roomId), chatPresenceService.getLastSeenByUser(roomId));
    }

    @GetMapping("/rooms/{roomId}/participants")
    @PreAuthorize("isAuthenticated()")
    public List<UserProfileDto> participants(@PathVariable UUID roomId) {
        return chatService.roomParticipants(roomId, SecurityUtils.requireCurrentUser().id());
    }

    public record DirectRoomRequest(UUID secondUserId) {}
    public record GroupRoomRequest(String title, Set<UUID> participantIds) {}
    public record CourseRoomRequest(UUID courseId) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendMessageRequest(String content, List<ChatAttachmentRequestDto> attachments) {
        public SendMessageRequest {
            attachments = attachments == null ? List.of() : attachments;
        }
    }
    public record MarkReadRequest(UUID messageId) {}
}

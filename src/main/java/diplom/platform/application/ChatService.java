package diplom.platform.application;

import diplom.platform.communication.domain.ChatRoomType;
import diplom.platform.communication.infrastructure.*;
import diplom.platform.infrastructure.events.ChatRealtimeGateway;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import diplom.platform.ui.dto.ChatAttachmentDto;
import diplom.platform.ui.dto.ChatAttachmentRequestDto;
import diplom.platform.ui.dto.ChatMessageDto;
import diplom.platform.ui.dto.ChatReadReceiptDto;
import diplom.platform.ui.dto.ChatRoomDto;
import diplom.platform.ui.dto.UserProfileDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatService {
    private final ChatRoomJpaRepository rooms;
    private final ChatParticipantJpaRepository participants;
    private final ChatMessageJpaRepository messages;
    private final ChatReadReceiptJpaRepository readReceipts;
    private final UserJpaRepository users;
    private final CourseJpaRepository courses;
    private final ChatRealtimeGateway realtimeGateway;

    public ChatService(ChatRoomJpaRepository rooms, ChatParticipantJpaRepository participants, ChatMessageJpaRepository messages,
                       ChatReadReceiptJpaRepository readReceipts,
                       UserJpaRepository users, CourseJpaRepository courses, ChatRealtimeGateway realtimeGateway) {
        this.rooms = rooms;
        this.participants = participants;
        this.messages = messages;
        this.readReceipts = readReceipts;
        this.users = users;
        this.courses = courses;
        this.realtimeGateway = realtimeGateway;
    }

    @Transactional(readOnly = true)
    public List<ChatRoomDto> listRooms(UUID userId) {
        return rooms.findForUser(userId).stream()
                .map(room -> toRoomDto(room, userId, unreadCount(room.getId(), userId)))
                .toList();
    }

    @Transactional
    public ChatRoomDto createDirectRoom(UUID creatorId, UUID secondUserId) {
        if (creatorId.equals(secondUserId)) {
            throw new IllegalArgumentException("Cannot create chat with yourself");
        }
        UserEntity creator = users.findById(creatorId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        UserEntity second = users.findById(secondUserId).orElseThrow(() -> new IllegalArgumentException("Target user not found"));
        Optional<ChatRoomEntity> existing = rooms.findDirectRoomBetweenUsers(creatorId, secondUserId, ChatRoomType.DIRECT);
        if (existing.isPresent()) {
            ChatRoomEntity r = existing.get();
            return toRoomDto(r, creatorId, unreadCount(r.getId(), creatorId));
        }
        ChatRoomEntity room = new ChatRoomEntity();
        room.setRoomType(ChatRoomType.DIRECT);
        room.setTitle(formatUserDisplayName(second));
        room.setCreatedBy(creator);
        room = rooms.save(room);
        addParticipant(room, creator);
        addParticipant(room, second);
        return toRoomDto(room, creatorId, 0);
    }

    @Transactional
    public ChatRoomDto createGroupRoom(UUID creatorId, String title, Set<UUID> participantIds) {
        UserEntity creator = users.findById(creatorId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatRoomEntity room = new ChatRoomEntity();
        room.setRoomType(ChatRoomType.GROUP);
        room.setTitle(title);
        room.setCreatedBy(creator);
        room = rooms.save(room);
        addParticipant(room, creator);
        for (UUID participantId : participantIds) {
            UserEntity participant = users.findById(participantId).orElseThrow(() -> new IllegalArgumentException("User not found"));
            addParticipant(room, participant);
        }
        return toRoomDto(room, creatorId, 0);
    }

    @Transactional
    public ChatRoomDto createCourseRoom(UUID creatorId, UUID courseId) {
        UserEntity creator = users.findById(creatorId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        CourseEntity course = courses.findOneWithDetails(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found"));
        if (creator.getRole() == UserRole.ROLE_TEACHER && (course.getOwner() == null || !course.getOwner().getId().equals(creatorId))) {
            throw new AccessDeniedException("Teacher can only create rooms for own courses");
        }
        List<ChatRoomEntity> existing = rooms.findForUser(creatorId).stream()
                .filter(r -> r.getRoomType() == ChatRoomType.COURSE && r.getCourse() != null && r.getCourse().getId().equals(courseId))
                .toList();
        if (!existing.isEmpty()) {
            ChatRoomEntity first = existing.getFirst();
            return toRoomDto(first, creatorId, unreadCount(first.getId(), creatorId));
        }
        ChatRoomEntity room = new ChatRoomEntity();
        room.setRoomType(ChatRoomType.COURSE);
        room.setTitle("Курс: " + course.getTitle());
        room.setCreatedBy(creator);
        room.setCourse(course);
        room = rooms.save(room);
        addParticipant(room, creator);
        for (UserEntity student : course.getStudents()) {
            addParticipant(room, student);
        }
        return toRoomDto(room, creatorId, 0);
    }

    @Transactional
    public void deleteRoom(UUID roomId, UUID userId) {
        ensureParticipant(roomId, userId);
        rooms.deleteById(roomId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> roomHistory(UUID roomId, UUID userId) {
        ensureParticipant(roomId, userId);
        List<ChatMessageDto> desc = new ArrayList<>(
                messages.findTop100ByRoom_IdOrderByCreatedAtDesc(roomId).stream().map(ChatService::toMessageDto).toList());
        java.util.Collections.reverse(desc);
        return desc;
    }

    @Transactional
    public ChatMessageDto sendMessage(UUID roomId, UUID senderId, String content,
                                      List<ChatAttachmentRequestDto> attachmentRequests) {
        List<ChatAttachmentRequestDto> raw = attachmentRequests == null ? List.of() : attachmentRequests;
        if (raw.size() > 3) {
            throw new IllegalArgumentException("At most 3 attachments per message");
        }
        List<ChatAttachmentRequestDto> cleaned = raw.stream()
                .filter(a -> a.fileKey() != null && !a.fileKey().isBlank())
                .limit(3)
                .toList();
        boolean hasText = content != null && !content.trim().isEmpty();
        if (!hasText && cleaned.isEmpty()) {
            throw new IllegalArgumentException("Message must have text or attachment");
        }
        UserEntity sender = users.findById(senderId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatRoomEntity room = rooms.findById(roomId).orElseThrow(() -> new IllegalArgumentException("Room not found"));
        ensureParticipant(roomId, senderId);
        ChatMessageEntity message = new ChatMessageEntity();
        message.setRoom(room);
        message.setSender(sender);
        message.setContent(hasText ? content.trim() : "");
        int idx = 0;
        for (ChatAttachmentRequestDto req : cleaned) {
            ChatMessageAttachmentEntity a = new ChatMessageAttachmentEntity();
            a.setFileKey(req.fileKey().trim());
            a.setOriginalName(req.originalName() != null && !req.originalName().isBlank() ? req.originalName() : "file");
            a.setContentType(req.contentType());
            a.setSortIndex(idx++);
            a.setMessage(message);
            message.getAttachments().add(a);
        }
        message = messages.save(message);
        ChatMessageDto dto = toMessageDto(message);
        realtimeGateway.broadcastMessage(dto);
        return dto;
    }

    @Transactional
    public ChatReadReceiptDto markRead(UUID roomId, UUID userId, UUID messageId) {
        ensureParticipant(roomId, userId);
        ChatMessageEntity message = messages.findById(messageId).orElseThrow(() -> new IllegalArgumentException("Message not found"));
        if (!message.getRoom().getId().equals(roomId)) {
            throw new IllegalArgumentException("Message does not belong to room");
        }
        UserEntity user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatReadReceiptEntity receipt = readReceipts.findByRoom_IdAndUser_Id(roomId, userId).orElseGet(ChatReadReceiptEntity::new);
        receipt.setRoom(message.getRoom());
        receipt.setUser(user);
        receipt.setMessage(message);
        receipt.setReadAt(java.time.OffsetDateTime.now());
        receipt = readReceipts.save(receipt);
        ChatReadReceiptDto dto = toReadDto(receipt);
        realtimeGateway.broadcastReadReceipt(roomId, dto);
        return dto;
    }

    @Transactional(readOnly = true)
    public List<ChatReadReceiptDto> readReceipts(UUID roomId, UUID userId) {
        ensureParticipant(roomId, userId);
        return readReceipts.findByRoom_Id(roomId).stream().map(ChatService::toReadDto).toList();
    }

    @Transactional(readOnly = true)
    public boolean isParticipant(UUID roomId, UUID userId) {
        return participants.existsByRoom_IdAndUser_Id(roomId, userId);
    }

    @Transactional(readOnly = true)
    public List<UserProfileDto> roomParticipants(UUID roomId, UUID userId) {
        ensureParticipant(roomId, userId);
        return participants.findByRoom_Id(roomId).stream()
                .map(ChatParticipantEntity::getUser)
                .map(u -> new UserProfileDto(
                        u.getId(),
                        u.getFirstName(),
                        u.getLastName(),
                        u.getMiddleName(),
                        u.getEmail(),
                        u.getRole(),
                        u.getAvatarFileKey(),
                        u.getBio()
                ))
                .toList();
    }

    private void ensureParticipant(UUID roomId, UUID userId) {
        if (!isParticipant(roomId, userId)) {
            throw new AccessDeniedException("User is not a participant of this room");
        }
    }

    private void addParticipant(ChatRoomEntity room, UserEntity user) {
        if (participants.existsByRoom_IdAndUser_Id(room.getId(), user.getId())) {
            return;
        }
        ChatParticipantEntity participant = new ChatParticipantEntity();
        participant.setRoom(room);
        participant.setUser(user);
        participants.save(participant);
    }

    private long unreadCount(UUID roomId, UUID userId) {
        Optional<ChatReadReceiptEntity> receiptOpt = readReceipts.findByRoom_IdAndUser_Id(roomId, userId);
        if (receiptOpt.isEmpty()) {
            return messages.countByRoom_IdAndSender_IdNot(roomId, userId);
        }
        OffsetDateTime after = receiptOpt.get().getMessage().getCreatedAt();
        return messages.countByRoom_IdAndSender_IdNotAndCreatedAtAfter(roomId, userId, after);
    }

    private ChatRoomDto toRoomDto(ChatRoomEntity room, UUID viewerId, long unreadCount) {
        return new ChatRoomDto(
                room.getId(),
                room.getRoomType(),
                resolveRoomTitle(room, viewerId),
                resolveCounterpartAvatarFileKey(room, viewerId),
                room.getCourse() != null ? room.getCourse().getId() : null,
                room.getCreatedAt(),
                unreadCount
        );
    }

    private String resolveRoomTitle(ChatRoomEntity room, UUID viewerId) {
        if (room.getRoomType() != ChatRoomType.DIRECT) {
            return room.getTitle();
        }
        List<ChatParticipantEntity> parts = participants.findByRoom_Id(room.getId());
        return parts.stream()
                .map(ChatParticipantEntity::getUser)
                .filter(u -> !u.getId().equals(viewerId))
                .findFirst()
                .map(ChatService::formatUserDisplayName)
                .orElse(room.getTitle() != null ? room.getTitle() : "Чат");
    }

    private String resolveCounterpartAvatarFileKey(ChatRoomEntity room, UUID viewerId) {
        if (room.getRoomType() != ChatRoomType.DIRECT) {
            return null;
        }
        List<ChatParticipantEntity> parts = participants.findByRoom_Id(room.getId());
        return parts.stream()
                .map(ChatParticipantEntity::getUser)
                .filter(u -> !u.getId().equals(viewerId))
                .findFirst()
                .map(UserEntity::getAvatarFileKey)
                .orElse(null);
    }

    private static String formatUserDisplayName(UserEntity u) {
        String fn = u.getFirstName() != null ? u.getFirstName() : "";
        String ln = u.getLastName() != null ? u.getLastName() : "";
        String s = (fn + " " + ln).trim();
        return s.isEmpty() ? "Пользователь" : s;
    }

    private static ChatMessageDto toMessageDto(ChatMessageEntity message) {
        List<ChatAttachmentDto> atts = message.getAttachments().stream()
                .sorted(Comparator.comparingInt(ChatMessageAttachmentEntity::getSortIndex))
                .map(a -> new ChatAttachmentDto(a.getFileKey(), a.getOriginalName(), a.getContentType()))
                .toList();
        return new ChatMessageDto(
                message.getId(),
                message.getRoom().getId(),
                message.getSender().getId(),
                message.getSender().getFirstName() + " " + message.getSender().getLastName(),
                message.getContent(),
                message.getCreatedAt(),
                atts
        );
    }

    private static ChatReadReceiptDto toReadDto(ChatReadReceiptEntity receipt) {
        return new ChatReadReceiptDto(
                receipt.getUser().getId(),
                receipt.getUser().getFirstName() + " " + receipt.getUser().getLastName(),
                receipt.getMessage().getId(),
                receipt.getReadAt()
        );
    }
}

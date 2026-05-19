package diplom.platform.communication.infrastructure;

import diplom.platform.identity.infrastructure.UserEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_read_receipts")
public class ChatReadReceiptEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private ChatRoomEntity room;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private ChatMessageEntity message;

    @Column(nullable = false)
    private OffsetDateTime readAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public ChatRoomEntity getRoom() { return room; }
    public void setRoom(ChatRoomEntity room) { this.room = room; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public ChatMessageEntity getMessage() { return message; }
    public void setMessage(ChatMessageEntity message) { this.message = message; }
    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
}

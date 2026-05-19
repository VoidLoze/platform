package diplom.platform.communication.infrastructure;

import diplom.platform.identity.infrastructure.UserEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_participants")
public class ChatParticipantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private ChatRoomEntity room;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(nullable = false)
    private OffsetDateTime joinedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public ChatRoomEntity getRoom() { return room; }
    public void setRoom(ChatRoomEntity room) { this.room = room; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime joinedAt) { this.joinedAt = joinedAt; }
}

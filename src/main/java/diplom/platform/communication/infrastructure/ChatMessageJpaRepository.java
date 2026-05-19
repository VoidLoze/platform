package diplom.platform.communication.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageEntity, UUID> {
    @EntityGraph(attributePaths = {"sender", "attachments"})
    List<ChatMessageEntity> findTop100ByRoom_IdOrderByCreatedAtDesc(UUID roomId);

    long countByRoom_IdAndSender_IdNotAndCreatedAtAfter(UUID roomId, UUID senderIdNot, OffsetDateTime createdAfter);

    long countByRoom_IdAndSender_IdNot(UUID roomId, UUID senderIdNot);
}

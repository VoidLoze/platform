package diplom.platform.communication.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatReadReceiptJpaRepository extends JpaRepository<ChatReadReceiptEntity, UUID> {
    Optional<ChatReadReceiptEntity> findByRoom_IdAndUser_Id(UUID roomId, UUID userId);

    @EntityGraph(attributePaths = {"user", "message"})
    List<ChatReadReceiptEntity> findByRoom_Id(UUID roomId);
}

package diplom.platform.communication.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatParticipantJpaRepository extends JpaRepository<ChatParticipantEntity, UUID> {
    boolean existsByRoom_IdAndUser_Id(UUID roomId, UUID userId);

    @EntityGraph(attributePaths = {"user"})
    List<ChatParticipantEntity> findByRoom_Id(UUID roomId);

    List<ChatParticipantEntity> findByUser_Id(UUID userId);
}

package diplom.platform.aicheck.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiCheckJobJpaRepository extends JpaRepository<AiCheckJobEntity, UUID> {

    @Query("select j from AiCheckJobEntity j left join fetch j.labWork where j.id = :id")
    Optional<AiCheckJobEntity> findByIdWithLabWork(UUID id);

    @Query("select j from AiCheckJobEntity j where j.status = diplom.platform.aicheck.infrastructure.AiCheckJobEntity.Status.QUEUED order by j.createdAt asc")
    List<AiCheckJobEntity> findQueued();

    List<AiCheckJobEntity> findByRequester_IdOrderByCreatedAtDesc(UUID requesterId);

    List<AiCheckJobEntity> findByLabWork_Id(UUID labWorkId);
}

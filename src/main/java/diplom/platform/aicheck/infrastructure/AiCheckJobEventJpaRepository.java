package diplom.platform.aicheck.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiCheckJobEventJpaRepository extends JpaRepository<AiCheckJobEventEntity, Long> {
    List<AiCheckJobEventEntity> findByJob_IdAndIdGreaterThanOrderByIdAsc(UUID jobId, long afterId);

    List<AiCheckJobEventEntity> findByJob_IdOrderByIdAsc(UUID jobId);
}

package diplom.platform.aicheck.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiCheckResultJpaRepository extends JpaRepository<AiCheckResultEntity, UUID> {
    List<AiCheckResultEntity> findByJob_IdOrderByCreatedAtAsc(UUID jobId);

    void deleteByJob_Id(UUID jobId);
}

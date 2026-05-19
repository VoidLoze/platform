package diplom.platform.evaluation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlagiarismReportJpaRepository extends JpaRepository<PlagiarismReportEntity, UUID> {
    Optional<PlagiarismReportEntity> findByLabWorkId(UUID labWorkId);
}

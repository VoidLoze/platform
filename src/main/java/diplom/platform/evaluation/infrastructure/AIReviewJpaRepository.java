package diplom.platform.evaluation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AIReviewJpaRepository extends JpaRepository<AIReviewEntity, UUID> {
    Optional<AIReviewEntity> findByLabWorkId(UUID labWorkId);
}

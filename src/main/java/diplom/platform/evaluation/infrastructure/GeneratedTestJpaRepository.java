package diplom.platform.evaluation.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GeneratedTestJpaRepository extends JpaRepository<GeneratedTestEntity, UUID> {
    Optional<GeneratedTestEntity> findByLabWorkId(UUID labWorkId);

    @EntityGraph(attributePaths = {"questions"})
    @Query("select distinct t from GeneratedTestEntity t where t.labWork.id = :labWorkId")
    Optional<GeneratedTestEntity> findWithQuestionsByLabWorkId(@Param("labWorkId") UUID labWorkId);
}

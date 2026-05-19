package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssignmentTestJpaRepository extends JpaRepository<AssignmentTestEntity, UUID> {
    @EntityGraph(attributePaths = {"questions"})
    Optional<AssignmentTestEntity> findByAssignment_Id(UUID assignmentId);
}

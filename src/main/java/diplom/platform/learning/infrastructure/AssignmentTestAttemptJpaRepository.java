package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssignmentTestAttemptJpaRepository extends JpaRepository<AssignmentTestAttemptEntity, UUID> {
    Optional<AssignmentTestAttemptEntity> findTopByTest_IdAndStudent_IdOrderBySubmittedAtDesc(UUID testId, UUID studentId);
    boolean existsByTest_IdAndStudent_Id(UUID testId, UUID studentId);
    boolean existsByTest_Assignment_IdAndStudent_Id(UUID assignmentId, UUID studentId);

    void deleteByTest_Id(UUID testId);
}

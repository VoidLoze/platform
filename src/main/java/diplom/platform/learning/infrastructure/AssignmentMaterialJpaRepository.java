package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssignmentMaterialJpaRepository extends JpaRepository<AssignmentMaterialEntity, UUID> {
    List<AssignmentMaterialEntity> findByAssignment_IdOrderByUploadedAtDesc(UUID assignmentId);

    void deleteByAssignment_Id(UUID assignmentId);
}

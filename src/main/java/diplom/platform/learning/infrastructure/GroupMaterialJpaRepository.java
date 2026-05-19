package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupMaterialJpaRepository extends JpaRepository<GroupMaterialEntity, UUID> {
    List<GroupMaterialEntity> findByCourse_IdOrderByUploadedAtDesc(UUID courseId);
}

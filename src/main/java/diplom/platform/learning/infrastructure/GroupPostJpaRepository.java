package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GroupPostJpaRepository extends JpaRepository<GroupPostEntity, UUID> {
    @Query("select distinct p from GroupPostEntity p left join fetch p.attachments left join fetch p.author "
            + "where p.course.id = :courseId order by p.pinned desc, p.createdAt desc")
    List<GroupPostEntity> findAllForCourse(@Param("courseId") UUID courseId);
}

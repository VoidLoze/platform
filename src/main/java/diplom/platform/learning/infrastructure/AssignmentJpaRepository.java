package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentJpaRepository extends JpaRepository<AssignmentEntity, UUID> {
    @EntityGraph(attributePaths = {"course"})
    List<AssignmentEntity> findByCourseIdOrderByDueDateAsc(UUID courseId);

    @Query("select distinct a from AssignmentEntity a join fetch a.course c left join fetch c.owner where a.id = :id")
    Optional<AssignmentEntity> findWithCourseForApi(@Param("id") UUID id);
}

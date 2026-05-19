package diplom.platform.evaluation.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabWorkJpaRepository extends JpaRepository<LabWorkEntity, UUID> {
    @EntityGraph(attributePaths = {"assignment", "assignment.course"})
    @Query("select lw from LabWorkEntity lw where lw.student.id = :studentId order by lw.submissionTime desc nulls last")
    List<LabWorkEntity> findMineWithAssignment(@Param("studentId") UUID studentId);

    @EntityGraph(attributePaths = {"student"})
    List<LabWorkEntity> findByAssignment_IdOrderBySubmissionTimeDesc(UUID assignmentId);

    @EntityGraph(attributePaths = {"student", "assignment"})
    List<LabWorkEntity> findByAssignment_Course_Id(UUID courseId);

    Optional<LabWorkEntity> findByAssignmentIdAndStudentId(UUID assignmentId, UUID studentId);

    @Query("select distinct lw from LabWorkEntity lw join fetch lw.student join fetch lw.assignment a join fetch a.course c left join fetch c.owner where lw.id = :id")
    Optional<LabWorkEntity> findDetailById(@Param("id") UUID id);
}

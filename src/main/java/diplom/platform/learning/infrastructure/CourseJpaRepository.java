package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseJpaRepository extends JpaRepository<CourseEntity, UUID> {
    @Query("select distinct c from CourseEntity c left join fetch c.students left join fetch c.owner order by c.title asc")
    List<CourseEntity> findAllCatalog();

    @Query("select distinct c from CourseEntity c left join fetch c.students left join fetch c.owner where c.owner.id = :ownerId order by c.title asc")
    List<CourseEntity> findTeacherCourses(@Param("ownerId") UUID ownerId);

    @Query("select distinct c from CourseEntity c left join fetch c.owner left join fetch c.students where c.id = :id")
    Optional<CourseEntity> findOneWithDetails(@Param("id") UUID id);

    @Query("select distinct c from CourseEntity c inner join c.students s left join fetch c.owner left join fetch c.students where s.id = :studentId order by c.title asc")
    List<CourseEntity> findEnrolledForStudent(@Param("studentId") UUID studentId);
}

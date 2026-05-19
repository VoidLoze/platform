package diplom.platform.learning.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarTaskJpaRepository extends JpaRepository<CalendarTaskEntity, UUID> {
    @EntityGraph(attributePaths = {"assignment", "course"})
    List<CalendarTaskEntity> findByUser_IdAndDueDateBetweenOrderByDueDateAsc(UUID userId, OffsetDateTime from, OffsetDateTime to);

    @EntityGraph(attributePaths = {"assignment", "course"})
    List<CalendarTaskEntity> findByUser_IdOrderByDueDateAsc(UUID userId);

    Optional<CalendarTaskEntity> findByUser_IdAndAssignment_Id(UUID userId, UUID assignmentId);

    void deleteByAssignment_Id(UUID assignmentId);
}

package diplom.platform.application;

import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.learning.domain.CalendarTaskStatus;
import diplom.platform.learning.infrastructure.*;
import diplom.platform.ui.dto.CalendarTaskDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class CalendarService {
    private final CalendarTaskJpaRepository calendarTasks;
    private final AssignmentJpaRepository assignments;
    private final CourseJpaRepository courses;
    private final UserJpaRepository users;
    private final LabWorkJpaRepository labWorks;
    private final AssignmentTestAttemptJpaRepository testAttempts;

    public CalendarService(CalendarTaskJpaRepository calendarTasks, AssignmentJpaRepository assignments, CourseJpaRepository courses,
                           UserJpaRepository users, LabWorkJpaRepository labWorks,
                           AssignmentTestAttemptJpaRepository testAttempts) {
        this.calendarTasks = calendarTasks;
        this.assignments = assignments;
        this.courses = courses;
        this.users = users;
        this.labWorks = labWorks;
        this.testAttempts = testAttempts;
    }

    @Transactional
    public List<CalendarTaskDto> getStudentCalendar(UUID studentId, OffsetDateTime from, OffsetDateTime to) {
        syncStudentTasks(studentId);
        List<CalendarTaskEntity> tasks = (from != null && to != null)
                ? calendarTasks.findByUser_IdAndDueDateBetweenOrderByDueDateAsc(studentId, from, to)
                : calendarTasks.findByUser_IdOrderByDueDateAsc(studentId);
        return tasks.stream().map(CalendarService::toDto).toList();
    }

    @Transactional
    public List<CalendarTaskDto> getTeacherCalendar(UUID teacherId) {
        UserEntity teacher = users.findById(teacherId).orElseThrow(() -> new IllegalArgumentException("Teacher not found"));
        List<CourseEntity> ownedCourses = courses.findTeacherCourses(teacher.getId());
        List<CalendarTaskDto> result = new ArrayList<>();
        for (CourseEntity c : ownedCourses) {
            List<AssignmentEntity> courseAssignments = assignments.findByCourseIdOrderByDueDateAsc(c.getId());
            for (AssignmentEntity a : courseAssignments) {
                result.add(new CalendarTaskDto(
                        a.getId(),
                        a.getId(),
                        c.getId(),
                        c.getTitle(),
                        c.getAvatarFileKey(),
                        a.getTopicTitle(),
                        a.getDueDate(),
                        a.getDueDate().isBefore(OffsetDateTime.now()) ? CalendarTaskStatus.OVERDUE : CalendarTaskStatus.OPEN
                ));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(CalendarTaskDto::dueDate))
                .toList();
    }

    private void syncStudentTasks(UUID studentId) {
        UserEntity student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        OffsetDateTime now = OffsetDateTime.now();
        for (CourseEntity course : courses.findEnrolledForStudent(studentId)) {
            for (AssignmentEntity assignment : assignments.findByCourseIdOrderByDueDateAsc(course.getId())) {
                CalendarTaskEntity task = calendarTasks.findByUser_IdAndAssignment_Id(studentId, assignment.getId()).orElseGet(() -> {
                    CalendarTaskEntity created = new CalendarTaskEntity();
                    created.setUser(student);
                    created.setCourse(course);
                    created.setAssignment(assignment);
                    created.setTitle(assignment.getTopicTitle());
                    created.setDueDate(assignment.getDueDate());
                    return created;
                });
                boolean submitted = labWorks.findByAssignmentIdAndStudentId(assignment.getId(), studentId)
                        .filter(lw -> lw.getStatus() != LabWorkStatus.DRAFT)
                        .isPresent();
                if (!submitted) {
                    submitted = testAttempts.existsByTest_Assignment_IdAndStudent_Id(assignment.getId(), studentId);
                }
                if (submitted) {
                    task.setStatus(CalendarTaskStatus.SUBMITTED);
                } else if (assignment.getDueDate().isBefore(now)) {
                    task.setStatus(CalendarTaskStatus.OVERDUE);
                } else {
                    task.setStatus(CalendarTaskStatus.OPEN);
                }
                calendarTasks.save(task);
            }
        }
    }

    private static CalendarTaskDto toDto(CalendarTaskEntity task) {
        return new CalendarTaskDto(
                task.getId(),
                task.getAssignment().getId(),
                task.getCourse().getId(),
                task.getCourse().getTitle(),
                task.getCourse().getAvatarFileKey(),
                task.getTitle(),
                task.getDueDate(),
                task.getStatus()
        );
    }
}

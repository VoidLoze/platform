package diplom.platform.application;

import diplom.platform.evaluation.infrastructure.LabWorkEntity;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import diplom.platform.learning.infrastructure.AssignmentJpaRepository;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import diplom.platform.ui.dto.CourseProgressDto;
import diplom.platform.ui.dto.StudentProgressDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ProgressService {
    private final CourseJpaRepository courses;
    private final AssignmentJpaRepository assignments;
    private final LabWorkJpaRepository labWorks;

    public ProgressService(CourseJpaRepository courses, AssignmentJpaRepository assignments, LabWorkJpaRepository labWorks) {
        this.courses = courses;
        this.assignments = assignments;
        this.labWorks = labWorks;
    }

    @Transactional(readOnly = true)
    public List<CourseProgressDto> getTeacherProgress(UUID teacherId) {
        return courses.findTeacherCourses(teacherId).stream()
                .map(this::buildCourseProgress)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseProgressDto getCourseProgress(UUID teacherId, UUID courseId) {
        CourseEntity course = courses.findOneWithDetails(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found"));
        if (course.getOwner() == null || !course.getOwner().getId().equals(teacherId)) {
            throw new org.springframework.security.access.AccessDeniedException("Forbidden");
        }
        return buildCourseProgress(course);
    }

    private CourseProgressDto buildCourseProgress(CourseEntity course) {
        List<AssignmentEntity> courseAssignments = assignments.findByCourseIdOrderByDueDateAsc(course.getId());
        List<LabWorkEntity> allSubmissions = new ArrayList<>();
        for (AssignmentEntity assignment : courseAssignments) {
            allSubmissions.addAll(labWorks.findByAssignment_IdOrderBySubmissionTimeDesc(assignment.getId()));
        }
        long gradedCount = allSubmissions.stream().filter(lw -> lw.getFinalGradeValue() != null).count();
        double averageGrade = gradedCount == 0
                ? 0.0
                : allSubmissions.stream().filter(lw -> lw.getFinalGradeValue() != null).mapToDouble(LabWorkEntity::getFinalGradeValue).average().orElse(0.0);

        List<StudentProgressDto> studentStats = course.getStudents().stream()
                .map(s -> buildStudentProgress(s, allSubmissions))
                .sorted(Comparator.comparing(StudentProgressDto::studentName))
                .toList();

        return new CourseProgressDto(
                course.getId(),
                course.getTitle(),
                courseAssignments.size(),
                allSubmissions.size(),
                averageGrade,
                studentStats
        );
    }

    private static StudentProgressDto buildStudentProgress(UserEntity student, List<LabWorkEntity> allSubmissions) {
        List<LabWorkEntity> mine = allSubmissions.stream()
                .filter(lw -> lw.getStudent().getId().equals(student.getId()))
                .toList();
        long graded = mine.stream().filter(lw -> lw.getFinalGradeValue() != null).count();
        double avg = graded == 0 ? 0.0 : mine.stream().filter(lw -> lw.getFinalGradeValue() != null)
                .mapToDouble(LabWorkEntity::getFinalGradeValue).average().orElse(0.0);
        return new StudentProgressDto(
                student.getId(),
                student.getFirstName() + " " + student.getLastName(),
                mine.size(),
                graded,
                avg
        );
    }
}

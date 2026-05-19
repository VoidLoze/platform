package diplom.platform.application;

import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.LabWorkEntity;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import diplom.platform.ui.dto.GradebookCourseDto;
import diplom.platform.ui.dto.GradebookEntryDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class GradebookService {
    private final CourseJpaRepository courses;
    private final LabWorkJpaRepository labWorks;

    public GradebookService(CourseJpaRepository courses, LabWorkJpaRepository labWorks) {
        this.courses = courses;
        this.labWorks = labWorks;
    }

    @Transactional(readOnly = true)
    public GradebookCourseDto getCourseJournal(UUID courseId, PlatformUser user) {
        CourseEntity course = courses.findOneWithDetails(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found"));
        if (user.role() == UserRole.ROLE_TEACHER && (course.getOwner() == null || !course.getOwner().getId().equals(user.id()))) {
            throw new AccessDeniedException("Forbidden");
        }
        List<GradebookEntryDto> entries = labWorks.findByAssignment_Course_Id(courseId).stream()
                .map(GradebookService::toEntry)
                .toList();
        return new GradebookCourseDto(course.getId(), course.getTitle(), entries);
    }

    @Transactional
    public GradebookEntryDto setManualGrade(UUID labWorkId, double gradeValue, PlatformUser user) {
        LabWorkEntity lab = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        if (user.role() == UserRole.ROLE_TEACHER) {
            UUID ownerId = lab.getAssignment().getCourse().getOwner() == null ? null : lab.getAssignment().getCourse().getOwner().getId();
            if (!user.id().equals(ownerId)) {
                throw new AccessDeniedException("Forbidden");
            }
            if (lab.getStatus() == LabWorkStatus.COMPLETED && lab.getFinalGradeValue() != null) {
                throw new IllegalArgumentException("Оценка уже выставлена и не может быть изменена");
            }
        }
        double max = lab.getAssignment().getMaxScore();
        double normalized = Math.max(0, Math.min(max, gradeValue));
        lab.setFinalGradeValue(normalized);
        lab.setFinalGradeLetter(normalized >= max * 0.6 ? "PASS" : "FAIL");
        lab.setStatus(LabWorkStatus.COMPLETED);
        return toEntry(labWorks.save(lab));
    }

    private static GradebookEntryDto toEntry(LabWorkEntity lw) {
        return new GradebookEntryDto(
                lw.getId(),
                lw.getAssignment().getId(),
                lw.getAssignment().getTopicTitle(),
                lw.getStudent().getId(),
                lw.getStudent().getFirstName() + " " + lw.getStudent().getLastName(),
                lw.getStudent().getEmail(),
                lw.getStatus(),
                lw.getSubmissionTime(),
                lw.getFinalGradeValue(),
                lw.getFinalGradeLetter()
        );
    }
}

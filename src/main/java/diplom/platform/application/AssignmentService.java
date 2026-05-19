package diplom.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.evaluation.domain.AIService;
import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.domain.NotificationService;
import diplom.platform.evaluation.infrastructure.LabWorkEntity;
import diplom.platform.ui.dto.SubmitLabResponseDto;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import diplom.platform.evaluation.infrastructure.OutboxEventEntity;
import diplom.platform.evaluation.infrastructure.OutboxEventJpaRepository;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import diplom.platform.learning.infrastructure.AssignmentJpaRepository;
import diplom.platform.learning.infrastructure.AssignmentMaterialJpaRepository;
import diplom.platform.learning.infrastructure.AssignmentTestAttemptJpaRepository;
import diplom.platform.learning.infrastructure.AssignmentTestAttemptEntity;
import diplom.platform.learning.infrastructure.AssignmentTestJpaRepository;
import diplom.platform.learning.infrastructure.CalendarTaskJpaRepository;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import diplom.platform.evaluation.infrastructure.AIReviewJpaRepository;
import diplom.platform.evaluation.infrastructure.GeneratedTestJpaRepository;
import diplom.platform.evaluation.infrastructure.PlagiarismReportJpaRepository;
import diplom.platform.aicheck.infrastructure.AiCheckJobJpaRepository;
import diplom.platform.aicheck.infrastructure.AiCheckResultJpaRepository;
import diplom.platform.ui.dto.AssignmentSubmissionRosterRowDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AssignmentService {
    private final AssignmentJpaRepository assignments;
    private final CourseJpaRepository courses;
    private final UserJpaRepository users;
    private final LabWorkJpaRepository labWorks;
    private final OutboxEventJpaRepository outbox;
    private final ObjectMapper objectMapper;
    private final AIService aiService;
    private final NotificationService notificationService;
    private final AIReviewJpaRepository aiReviews;
    private final GeneratedTestJpaRepository generatedTests;
    private final PlagiarismReportJpaRepository plagiarismReports;
    private final AiCheckJobJpaRepository aiCheckJobs;
    private final AiCheckResultJpaRepository aiCheckResults;
    private final AssignmentMaterialJpaRepository assignmentMaterials;
    private final AssignmentTestJpaRepository assignmentTests;
    private final AssignmentTestAttemptJpaRepository assignmentTestAttempts;
    private final CalendarTaskJpaRepository calendarTasks;
    private final CourseService courseService;

    public AssignmentService(AssignmentJpaRepository assignments, CourseJpaRepository courses, UserJpaRepository users,
                             LabWorkJpaRepository labWorks, OutboxEventJpaRepository outbox, ObjectMapper objectMapper,
                             AIService aiService, NotificationService notificationService, AIReviewJpaRepository aiReviews,
                             GeneratedTestJpaRepository generatedTests, PlagiarismReportJpaRepository plagiarismReports,
                             AiCheckJobJpaRepository aiCheckJobs, AiCheckResultJpaRepository aiCheckResults,
                             AssignmentMaterialJpaRepository assignmentMaterials, AssignmentTestJpaRepository assignmentTests,
                             AssignmentTestAttemptJpaRepository assignmentTestAttempts, CalendarTaskJpaRepository calendarTasks,
                             CourseService courseService) {
        this.assignments = assignments;
        this.courses = courses;
        this.users = users;
        this.labWorks = labWorks;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.aiService = aiService;
        this.notificationService = notificationService;
        this.aiReviews = aiReviews;
        this.generatedTests = generatedTests;
        this.plagiarismReports = plagiarismReports;
        this.aiCheckJobs = aiCheckJobs;
        this.aiCheckResults = aiCheckResults;
        this.assignmentMaterials = assignmentMaterials;
        this.assignmentTests = assignmentTests;
        this.assignmentTestAttempts = assignmentTestAttempts;
        this.calendarTasks = calendarTasks;
        this.courseService = courseService;
    }

    @Transactional
    public AssignmentEntity createAssignment(UUID courseId, String topicTitle, String description, String subjectArea,
                                             OffsetDateTime dueDate, boolean allowLate, double latePenalty, double maxScore) {
        CourseEntity course = courses.findById(courseId).orElseThrow(() -> new IllegalArgumentException("Course not found"));
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setCourse(course);
        assignment.setTopicTitle(topicTitle);
        assignment.setTopicDescription(description);
        assignment.setSubjectArea(subjectArea);
        assignment.setDueDate(dueDate);
        assignment.setAllowLateSubmission(allowLate);
        assignment.setLatePenaltyPercent(latePenalty);
        assignment.setMaxScore(maxScore);
        AssignmentEntity saved = assignments.save(assignment);
        for (UserEntity student : course.getStudents()) {
            notificationService.notifyUser(
                    student.getEmail(),
                    "В группе \"" + course.getTitle() + "\" появилось новое задание: " + topicTitle
            );
        }
        return saved;
    }

    public java.util.List<LabWorkEntity> listLabWorksForAssignment(UUID assignmentId) {
        return labWorks.findByAssignment_IdOrderBySubmissionTimeDesc(assignmentId);
    }

    /**
     * Все студенты группы по заданию: последняя текстовая отправка (если была) и результат прикреплённого теста.
     */
    @Transactional(readOnly = true)
    public List<AssignmentSubmissionRosterRowDto> listSubmissionRoster(UUID assignmentId, PlatformUser teacher) {
        AssignmentEntity assignment = assignments.findWithCourseForApi(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        courseService.assertTeacherOwnsOrAdmin(assignment.getCourse().getId(), teacher);
        CourseEntity course = courseService.getCourse(assignment.getCourse().getId());
        List<LabWorkEntity> allLabs = labWorks.findByAssignment_IdOrderBySubmissionTimeDesc(assignmentId);
        Map<UUID, List<LabWorkEntity>> byStudent = new HashMap<>();
        for (LabWorkEntity lw : allLabs) {
            byStudent.computeIfAbsent(lw.getStudent().getId(), k -> new ArrayList<>()).add(lw);
        }
        UUID testId = assignmentTests.findByAssignment_Id(assignmentId).map(t -> t.getId()).orElse(null);
        boolean assignmentHasTest = testId != null;
        Comparator<UserEntity> byName = Comparator
                .comparing(UserEntity::getLastName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(UserEntity::getFirstName, String.CASE_INSENSITIVE_ORDER);
        List<AssignmentSubmissionRosterRowDto> out = new ArrayList<>();
        for (UserEntity st : course.getStudents().stream().sorted(byName).toList()) {
            List<LabWorkEntity> labs = byStudent.getOrDefault(st.getId(), List.of());
            LabWorkEntity latest = labs.isEmpty() ? null : labs.get(0);
            boolean testTaken = false;
            Double testScore = null;
            if (testId != null) {
                Optional<AssignmentTestAttemptEntity> att =
                        assignmentTestAttempts.findTopByTest_IdAndStudent_IdOrderBySubmittedAtDesc(testId, st.getId());
                if (att.isPresent()) {
                    testTaken = true;
                    testScore = att.get().getScore();
                }
            }
            String labStatus = latest == null ? "NONE" : latest.getStatus().name();
            out.add(new AssignmentSubmissionRosterRowDto(
                    st.getId(),
                    st.getFirstName() + " " + st.getLastName(),
                    st.getEmail(),
                    latest == null ? null : latest.getId(),
                    labStatus,
                    latest == null ? null : latest.getSubmissionTime(),
                    latest == null ? null : latest.getFinalGradeValue(),
                    testScore,
                    testTaken,
                    labs.size(),
                    assignmentHasTest));
        }
        return out;
    }

    public java.util.List<AssignmentEntity> listAssignmentsForCourse(UUID courseId) {
        return assignments.findByCourseIdOrderByDueDateAsc(courseId);
    }

    @Transactional(readOnly = true)
    public AIService.GeneratedAssignmentDraft generateDraft(String subjectArea, String difficulty, String learningGoals) {
        return aiService.generateAssignmentDraft(subjectArea, difficulty, learningGoals);
    }

    @Transactional(readOnly = true)
    public AssignmentEntity getAssignment(UUID id) {
        return assignments.findWithCourseForApi(id).orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
    }

    @Transactional
    public SubmitLabResponseDto submitAssignment(UUID assignmentId, UUID studentId, String textContent, String attachmentKey, String language) {
        AssignmentEntity assignment = assignments.findById(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        UserEntity student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found"));

        String normalizedText = textContent == null || textContent.isBlank() ? null : textContent.trim();
        String normalizedAttachment = attachmentKey == null || attachmentKey.isBlank() ? null : attachmentKey.trim();
        if (normalizedText == null && normalizedAttachment == null) {
            throw new IllegalArgumentException("Укажите текст ответа или прикрепите файл");
        }

        LabWorkEntity labWork = new LabWorkEntity();
        labWork.setAssignment(assignment);
        labWork.setStudent(student);
        labWork.setStatus(LabWorkStatus.SUBMITTED);
        labWork.setTextContent(normalizedText);
        labWork.setAttachmentKey(normalizedAttachment);
        labWork.setLanguage(language);
        labWork.setSubmissionTime(OffsetDateTime.now());
        labWork = labWorks.save(labWork);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType("AssignmentSubmitted");
        try {
            event.setPayload(objectMapper.writeValueAsString(Map.of("assignmentId", assignmentId, "studentId", studentId, "labWorkId", labWork.getId())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event payload", e);
        }
        outbox.save(event);
        return new SubmitLabResponseDto(
                labWork.getId(),
                assignmentId,
                labWork.getStatus(),
                labWork.getSubmissionTime()
        );
    }

    @Transactional
    public void deleteLabWork(UUID labWorkId, PlatformUser actor) {
        LabWorkEntity labWork = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        UUID ownerId = labWork.getAssignment().getCourse().getOwner() == null ? null : labWork.getAssignment().getCourse().getOwner().getId();
        boolean canDelete = actor.role() == UserRole.ROLE_ADMIN || (ownerId != null && ownerId.equals(actor.id()));
        if (!canDelete) {
            throw new AccessDeniedException("Нет прав на удаление этой работы");
        }
        deleteLabWorkRelatedDataAndEntity(labWork);
    }

    @Transactional
    public void deleteAssignment(UUID assignmentId, PlatformUser actor) {
        AssignmentEntity assignment = assignments.findWithCourseForApi(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        UUID ownerId = assignment.getCourse().getOwner() == null ? null : assignment.getCourse().getOwner().getId();
        boolean canDelete = actor.role() == UserRole.ROLE_ADMIN || (ownerId != null && ownerId.equals(actor.id()));
        if (!canDelete) {
            throw new AccessDeniedException("Нет прав на удаление этого задания");
        }

        labWorks.findByAssignment_IdOrderBySubmissionTimeDesc(assignmentId)
                .forEach(this::deleteLabWorkRelatedDataAndEntity);
        assignmentTests.findByAssignment_Id(assignmentId).ifPresent(test -> {
            assignmentTestAttempts.deleteByTest_Id(test.getId());
            assignmentTests.delete(test);
        });
        assignmentMaterials.deleteByAssignment_Id(assignmentId);
        calendarTasks.deleteByAssignment_Id(assignmentId);
        assignments.delete(assignment);
    }

    private void deleteLabWorkRelatedDataAndEntity(LabWorkEntity labWork) {
        UUID labWorkId = labWork.getId();
        aiReviews.findByLabWorkId(labWorkId).ifPresent(aiReviews::delete);
        generatedTests.findByLabWorkId(labWorkId).ifPresent(generatedTests::delete);
        plagiarismReports.findByLabWorkId(labWorkId).ifPresent(plagiarismReports::delete);
        aiCheckJobs.findByLabWork_Id(labWorkId).forEach(job -> {
            aiCheckResults.deleteByJob_Id(job.getId());
            aiCheckJobs.delete(job);
        });
        labWorks.delete(labWork);
    }
}

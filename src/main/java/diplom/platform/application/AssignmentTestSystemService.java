package diplom.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.evaluation.domain.AIService;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.learning.infrastructure.*;
import diplom.platform.ui.dto.AssignmentTestDto;
import diplom.platform.ui.dto.AssignmentTestQuestionDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssignmentTestSystemService {
    private final AssignmentJpaRepository assignments;
    private final AssignmentTestJpaRepository tests;
    private final AssignmentTestAttemptJpaRepository attempts;
    private final UserJpaRepository users;
    private final ObjectMapper objectMapper;
    private final AIService aiService;

    public AssignmentTestSystemService(AssignmentJpaRepository assignments, AssignmentTestJpaRepository tests,
                                       AssignmentTestAttemptJpaRepository attempts, UserJpaRepository users,
                                       ObjectMapper objectMapper, AIService aiService) {
        this.assignments = assignments;
        this.tests = tests;
        this.attempts = attempts;
        this.users = users;
        this.objectMapper = objectMapper;
        this.aiService = aiService;
    }

    @Transactional(readOnly = true)
    public AssignmentTestDto generatePreview(UUID assignmentId, int questionCount, String notes) {
        if (questionCount < 1 || questionCount > 50) {
            throw new IllegalArgumentException("Количество вопросов: от 1 до 50");
        }
        AssignmentEntity assignment = assignments.findById(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        StringBuilder ctx = new StringBuilder();
        ctx.append("Тема задания: ").append(assignment.getTopicTitle()).append('\n');
        if (assignment.getTopicDescription() != null && !assignment.getTopicDescription().isBlank()) {
            ctx.append("Описание: ").append(assignment.getTopicDescription()).append('\n');
        }
        if (assignment.getSubjectArea() != null && !assignment.getSubjectArea().isBlank()) {
            ctx.append("Предметная область: ").append(assignment.getSubjectArea()).append('\n');
        }
        if (notes != null && !notes.isBlank()) {
            ctx.append("Пожелания преподавателя: ").append(notes).append('\n');
        }
        AIService.GeneratedTestResult result = aiService.generateTest(ctx.toString(), questionCount);
        List<AIService.GeneratedQuestion> gen = result.questions();
        double perQuestion = !gen.isEmpty() && result.maxScore() > 0 ? result.maxScore() / gen.size() : 1.0;
        List<AssignmentTestQuestionDto> qs = new ArrayList<>();
        for (AIService.GeneratedQuestion q : gen) {
            qs.add(new AssignmentTestQuestionDto(
                    UUID.randomUUID(),
                    q.text(),
                    "single_choice",
                    q.options() == null ? List.of() : q.options(),
                    q.correctAnswer(),
                    perQuestion
            ));
        }
        return new AssignmentTestDto(null, "Сгенерированный тест", result.maxScore(), qs);
    }

    @Transactional(readOnly = true)
    public AssignmentTestDto generatePreviewFromDraft(String topicTitle, String topicDescription, String subjectArea, int questionCount, String notes) {
        if (questionCount < 1 || questionCount > 50) {
            throw new IllegalArgumentException("Количество вопросов: от 1 до 50");
        }
        StringBuilder ctx = new StringBuilder();
        ctx.append("Тема задания: ").append(topicTitle == null ? "" : topicTitle).append('\n');
        if (topicDescription != null && !topicDescription.isBlank()) {
            ctx.append("Описание: ").append(topicDescription).append('\n');
        }
        if (subjectArea != null && !subjectArea.isBlank()) {
            ctx.append("Предметная область: ").append(subjectArea).append('\n');
        }
        if (notes != null && !notes.isBlank()) {
            ctx.append("Пожелания преподавателя: ").append(notes).append('\n');
        }
        AIService.GeneratedTestResult result = aiService.generateTest(ctx.toString(), questionCount);
        List<AIService.GeneratedQuestion> gen = result.questions();
        double perQuestion = !gen.isEmpty() && result.maxScore() > 0 ? result.maxScore() / gen.size() : 1.0;
        List<AssignmentTestQuestionDto> qs = new ArrayList<>();
        for (AIService.GeneratedQuestion q : gen) {
            qs.add(new AssignmentTestQuestionDto(
                    UUID.randomUUID(),
                    q.text(),
                    "single_choice",
                    q.options() == null ? List.of() : q.options(),
                    q.correctAnswer(),
                    perQuestion
            ));
        }
        return new AssignmentTestDto(null, "Сгенерированный тест", result.maxScore(), qs);
    }

    @Transactional
    public AssignmentTestDto upsert(UUID assignmentId, String title, double maxScore, List<AssignmentTestQuestionDto> questionDtos) {
        AssignmentEntity assignment = assignments.findById(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        AssignmentTestEntity test = tests.findByAssignment_Id(assignmentId).orElseGet(AssignmentTestEntity::new);
        test.setAssignment(assignment);
        test.setTitle(title);
        test.setMaxScore(maxScore);
        test.getQuestions().clear();
        for (AssignmentTestQuestionDto q : questionDtos) {
            AssignmentTestQuestionEntity entity = new AssignmentTestQuestionEntity();
            entity.setTest(test);
            entity.setQuestionText(q.questionText());
            entity.setQuestionType(q.questionType());
            entity.setCorrectAnswer(q.correctAnswer());
            entity.setPoints(q.points());
            try {
                entity.setOptionsJson(objectMapper.writeValueAsString(q.options() == null ? List.of() : q.options()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to serialize question options", e);
            }
            test.getQuestions().add(entity);
        }
        return toDto(tests.save(test), true);
    }

    @Transactional(readOnly = true)
    public AssignmentTestDto get(UUID assignmentId, PlatformUser user) {
        AssignmentTestEntity test = tests.findByAssignment_Id(assignmentId).orElseThrow(() -> new IllegalArgumentException("Test not found"));
        boolean includeAnswers = user.role() == UserRole.ROLE_TEACHER || user.role() == UserRole.ROLE_ADMIN;
        return toDto(test, includeAnswers);
    }

    @Transactional
    public TestSubmitResult submit(UUID assignmentId, PlatformUser user, Map<UUID, String> answers) {
        if (user.role() != UserRole.ROLE_STUDENT) {
            throw new AccessDeniedException("Only students can submit tests");
        }
        AssignmentTestEntity test = tests.findByAssignment_Id(assignmentId).orElseThrow(() -> new IllegalArgumentException("Test not found"));
        if (attempts.existsByTest_IdAndStudent_Id(test.getId(), user.id())) {
            throw new IllegalStateException("Тест можно отправить только один раз");
        }
        double totalPoints = test.getQuestions().stream().mapToDouble(AssignmentTestQuestionEntity::getPoints).sum();
        if (totalPoints <= 0) {
            throw new IllegalStateException("Test points must be positive");
        }
        int correct = 0;
        double earnedPoints = 0.0;
        for (AssignmentTestQuestionEntity q : test.getQuestions()) {
            String given = answers.getOrDefault(q.getId(), "");
            if (q.getCorrectAnswer().trim().equalsIgnoreCase(given.trim())) {
                correct++;
                earnedPoints += q.getPoints();
            }
        }
        double score = (earnedPoints / totalPoints) * test.getMaxScore();
        UserEntity student = users.findById(user.id()).orElseThrow(() -> new IllegalArgumentException("User not found"));
        AssignmentTestAttemptEntity attempt = new AssignmentTestAttemptEntity();
        attempt.setTest(test);
        attempt.setStudent(student);
        attempt.setScore(score);
        attempt.setSubmittedAt(OffsetDateTime.now());
        try {
            attempt.setAnswersJson(objectMapper.writeValueAsString(answers));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize answers", e);
        }
        attempts.save(attempt);
        return new TestSubmitResult(correct, test.getQuestions().size(), score);
    }

    @Transactional(readOnly = true)
    public TestAttemptStatus myAttemptStatus(UUID assignmentId, PlatformUser user) {
        if (user.role() != UserRole.ROLE_STUDENT) {
            throw new AccessDeniedException("Only students can view own attempt status");
        }
        AssignmentTestEntity test = tests.findByAssignment_Id(assignmentId).orElseThrow(() -> new IllegalArgumentException("Test not found"));
        return attempts.findTopByTest_IdAndStudent_IdOrderBySubmittedAtDesc(test.getId(), user.id())
                .map(a -> new TestAttemptStatus(true, a.getScore()))
                .orElse(new TestAttemptStatus(false, null));
    }

    @Transactional(readOnly = true)
    public Double latestStudentScore(UUID assignmentId, UUID studentId) {
        AssignmentTestEntity test = tests.findByAssignment_Id(assignmentId).orElse(null);
        if (test == null) return null;
        return attempts.findTopByTest_IdAndStudent_IdOrderBySubmittedAtDesc(test.getId(), studentId).map(AssignmentTestAttemptEntity::getScore).orElse(null);
    }

    private AssignmentTestDto toDto(AssignmentTestEntity test, boolean includeAnswers) {
        List<AssignmentTestQuestionDto> questionDtos = test.getQuestions().stream().map(q -> {
            List<String> options;
            try {
                options = objectMapper.readValue(q.getOptionsJson() == null ? "[]" : q.getOptionsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                options = List.of();
            }
            return new AssignmentTestQuestionDto(
                    q.getId(),
                    q.getQuestionText(),
                    q.getQuestionType(),
                    options,
                    includeAnswers ? q.getCorrectAnswer() : null,
                    q.getPoints()
            );
        }).toList();
        return new AssignmentTestDto(test.getId(), test.getTitle(), test.getMaxScore(), questionDtos);
    }

    public record TestSubmitResult(int correct, int total, double score) {}
    public record TestAttemptStatus(boolean attempted, Double score) {}
}

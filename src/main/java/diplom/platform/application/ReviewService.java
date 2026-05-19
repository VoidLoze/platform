package diplom.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.evaluation.domain.AIService;
import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.*;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.infrastructure.security.PlatformUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {
    private final LabWorkJpaRepository labWorks;
    private final AIReviewJpaRepository reviews;
    private final GeneratedTestJpaRepository tests;
    private final OutboxEventJpaRepository outbox;
    private final AIService aiService;
    private final PlagiarismService plagiarismService;
    private final ObjectMapper objectMapper;

    public ReviewService(LabWorkJpaRepository labWorks, AIReviewJpaRepository reviews, GeneratedTestJpaRepository tests,
                         OutboxEventJpaRepository outbox, AIService aiService, PlagiarismService plagiarismService, ObjectMapper objectMapper) {
        this.labWorks = labWorks;
        this.reviews = reviews;
        this.tests = tests;
        this.outbox = outbox;
        this.aiService = aiService;
        this.plagiarismService = plagiarismService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AIReviewEntity generateReview(UUID labWorkId, PlatformUser actor) {
        LabWorkEntity labWork = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        authorizeTeacherReview(labWork, actor);
        labWork.setStatus(LabWorkStatus.IN_REVIEW);

        AIService.AIReviewResult result = aiService.analyze(
                labWork.getTextContent(),
                labWork.getAssignment().getMaxScore(),
                buildRubric(labWork));

        AIReviewEntity review = reviews.findByLabWorkId(labWorkId).orElse(new AIReviewEntity());
        review.setLabWork(labWork);
        review.setScoreValue(result.score());
        review.setMaxScore(labWork.getAssignment().getMaxScore());
        review.setSummary(result.summary());
        review.setDetailedFeedback(result.detailedFeedback());
        review.setRecommendations(result.recommendations());
        review = reviews.save(review);

        AIService.GeneratedTestResult generatedTest;
        try {
            generatedTest = aiService.generateTest(result.summary(), 5);
        } catch (Exception ex) {
            generatedTest = new AIService.GeneratedTestResult(100, java.util.List.of(
                    new AIService.GeneratedQuestion(
                            "Контрольный вопрос по итогам проверки",
                            "medium",
                            java.util.List.of("Вариант A", "Вариант B", "Вариант C", "Вариант D"),
                            "Вариант A"
                    )
            ));
        }
        GeneratedTestEntity test = tests.findByLabWorkId(labWorkId).orElse(new GeneratedTestEntity());
        test.setLabWork(labWork);
        test.setMaxScore(generatedTest.maxScore());
        test.getQuestions().clear();
        for (AIService.GeneratedQuestion q : generatedTest.questions()) {
            QuestionEntity question = new QuestionEntity();
            question.setTest(test);
            question.setText(q.text());
            question.setDifficulty(q.difficulty());
            question.setCorrectAnswer(q.correctAnswer());
            test.getQuestions().add(question);
        }
        tests.save(test);

        double similarity = plagiarismService.buildReport(labWork).getSimilarityScore();

        labWork.setStatus(LabWorkStatus.REVIEW_READY);
        double penalty = Math.min(0.5, similarity);
        double adjustedScore = Math.max(0, result.score() * (1.0 - penalty));
        labWork.setFinalGradeValue(adjustedScore);
        labWork.setFinalGradeLetter(adjustedScore >= labWork.getAssignment().getMaxScore() * 0.6 ? "PASS" : "FAIL");
        labWorks.save(labWork);

        publishOutbox("ReviewGenerated", Map.of("reviewId", review.getId(), "labWorkId", labWorkId, "score", result.score()));
        publishOutbox("TestCompleted", Map.of("testId", test.getId(), "result", "GENERATED"));
        return review;
    }

    private void authorizeTeacherReview(LabWorkEntity labWork, PlatformUser actor) {
        if (actor.role() == UserRole.ROLE_ADMIN) {
            return;
        }
        if (actor.role() != UserRole.ROLE_TEACHER) {
            throw new AccessDeniedException("Только преподаватель может запускать проверку");
        }
        var course = labWork.getAssignment().getCourse();
        UUID ownerId = course.getOwner() == null ? null : course.getOwner().getId();
        if (ownerId == null || !ownerId.equals(actor.id())) {
            throw new AccessDeniedException("Можно проверять только работы своей группы");
        }
    }

    private void publishOutbox(String eventType, Map<String, Object> payloadMap) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType(eventType);
        try {
            event.setPayload(objectMapper.writeValueAsString(payloadMap));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event payload", e);
        }
        event.setProcessedAt(OffsetDateTime.now());
        outbox.save(event);
    }

    private String buildRubric(LabWorkEntity labWork) {
        String subject = labWork.getAssignment().getSubjectArea() == null ? "GENERAL" : labWork.getAssignment().getSubjectArea().toUpperCase();
        return switch (subject) {
            case "CS" -> "Оцени по критериям: корректность алгоритма (40%), качество кода и структуры (30%), тестируемость и обработка ошибок (20%), объяснение решения (10%).";
            case "MATH" -> "Оцени по критериям: корректность вычислений и формул (45%), логика доказательства (30%), оформление решения и обозначения (15%), интерпретация результата (10%).";
            case "PHYSICS" -> "Оцени по критериям: корректность физических законов и расчетов (40%), анализ эксперимента/данных (30%), точность единиц и размерностей (20%), выводы (10%).";
            case "HISTORY" -> "Оцени по критериям: точность фактов и дат (35%), причинно-следственный анализ (35%), работа с источниками и аргументацией (20%), структура текста (10%).";
            default -> "Оцени по критериям точности, полноты, качества выполнения и объяснения решения.";
        };
    }
}

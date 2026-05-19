package diplom.platform.application;

import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.*;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.infrastructure.security.PlatformUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LabWorkTestService {
    private final LabWorkJpaRepository labWorks;
    private final GeneratedTestJpaRepository tests;
    private final AIReviewJpaRepository reviews;

    public LabWorkTestService(LabWorkJpaRepository labWorks, GeneratedTestJpaRepository tests, AIReviewJpaRepository reviews) {
        this.labWorks = labWorks;
        this.tests = tests;
        this.reviews = reviews;
    }

    @Transactional
    public TestSubmitResult submitTest(UUID labWorkId, PlatformUser user, Map<UUID, String> answers) {
        if (user.role() != UserRole.ROLE_STUDENT) {
            throw new AccessDeniedException("Only students submit tests");
        }
        LabWorkEntity labWork = labWorks.findById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        if (!labWork.getStudent().getId().equals(user.id())) {
            throw new AccessDeniedException("Not your submission");
        }
        GeneratedTestEntity test = tests.findByLabWorkId(labWorkId).orElseThrow(() -> new IllegalStateException("Test not generated yet"));
        List<QuestionEntity> questions = test.getQuestions();
        if (questions.isEmpty()) {
            throw new IllegalStateException("No questions in test");
        }
        int correct = 0;
        for (QuestionEntity q : questions) {
            String given = answers.getOrDefault(q.getId(), "");
            String expected = q.getCorrectAnswer() == null ? "" : q.getCorrectAnswer();
            if (expected.trim().equalsIgnoreCase(given.trim())) {
                correct++;
            }
        }
        double fraction = (double) correct / questions.size();
        double testScore = fraction * test.getMaxScore();
        double aiScore = reviews.findByLabWorkId(labWorkId).map(AIReviewEntity::getScoreValue).orElse(0.0);
        double combined = aiScore * 0.7 + testScore * 0.3;
        labWork.setFinalGradeValue(combined);
        labWork.setFinalGradeLetter(combined >= labWork.getAssignment().getMaxScore() * 0.6 ? "PASS" : "FAIL");
        labWork.setStatus(LabWorkStatus.COMPLETED);
        labWorks.save(labWork);
        return new TestSubmitResult(correct, questions.size(), testScore, combined);
    }

    public record TestSubmitResult(int correct, int total, double testScore, double finalCombinedScore) {
    }
}

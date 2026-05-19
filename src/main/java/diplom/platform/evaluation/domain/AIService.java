package diplom.platform.evaluation.domain;

import java.util.List;

public interface AIService {
    AIReviewResult analyze(String content, double maxScore, String rubric);

    GeneratedTestResult generateTest(String reviewSummary, int questionCount);

    GeneratedAssignmentDraft generateAssignmentDraft(String subjectArea, String difficulty, String learningGoals);

    record AIReviewResult(double score, String summary, String detailedFeedback, String recommendations) {}

    record GeneratedQuestion(String text, String difficulty, List<String> options, String correctAnswer) {}

    record GeneratedTestResult(double maxScore, List<GeneratedQuestion> questions) {}

    record GeneratedAssignmentDraft(
            String topicTitle,
            String topicDescription,
            String subjectArea,
            double recommendedMaxScore,
            double recommendedLatePenaltyPercent
    ) {}
}

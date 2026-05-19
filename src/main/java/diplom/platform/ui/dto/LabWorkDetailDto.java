package diplom.platform.ui.dto;

import diplom.platform.evaluation.domain.LabWorkStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LabWorkDetailDto(
        UUID id,
        UUID assignmentId,
        String assignmentTitle,
        UUID courseId,
        String courseTitle,
        LabWorkStatus status,
        String textContent,
        String attachmentKey,
        String language,
        OffsetDateTime submissionTime,
        Double finalGradeValue,
        String finalGradeLetter,
        AIReviewDto aiReview,
        GeneratedTestDto generatedTest,
        /** Для преподавателя: ФИО автора работы; для студента — null. */
        String studentDisplayName,
        double assignmentMaxScore,
        String assignmentDescription
) {
}

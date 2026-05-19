package diplom.platform.ui.dto;

import diplom.platform.evaluation.domain.LabWorkStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GradebookEntryDto(
        UUID labWorkId,
        UUID assignmentId,
        String assignmentTitle,
        UUID studentId,
        String studentName,
        String studentEmail,
        LabWorkStatus status,
        OffsetDateTime submissionTime,
        Double finalGradeValue,
        String finalGradeLetter
) {
}

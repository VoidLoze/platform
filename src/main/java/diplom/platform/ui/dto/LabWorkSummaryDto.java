package diplom.platform.ui.dto;

import diplom.platform.evaluation.domain.LabWorkStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LabWorkSummaryDto(
        UUID id,
        UUID assignmentId,
        String assignmentTitle,
        LabWorkStatus status,
        OffsetDateTime submissionTime,
        Double finalGradeValue,
        String finalGradeLetter
) {
}

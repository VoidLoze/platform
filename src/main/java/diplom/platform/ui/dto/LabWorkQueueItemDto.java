package diplom.platform.ui.dto;

import diplom.platform.evaluation.domain.LabWorkStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LabWorkQueueItemDto(
        UUID id,
        UUID studentId,
        String studentName,
        String studentEmail,
        LabWorkStatus status,
        OffsetDateTime submissionTime,
        Double finalGradeValue
) {
}

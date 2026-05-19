package diplom.platform.ui.dto;

import diplom.platform.evaluation.domain.LabWorkStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubmitLabResponseDto(
        UUID id,
        UUID assignmentId,
        LabWorkStatus status,
        OffsetDateTime submissionTime
) {
}

package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignmentDto(
        UUID id,
        UUID courseId,
        String topicTitle,
        String topicDescription,
        String subjectArea,
        OffsetDateTime dueDate,
        boolean allowLateSubmission,
        double latePenaltyPercent,
        double maxScore
) {
}

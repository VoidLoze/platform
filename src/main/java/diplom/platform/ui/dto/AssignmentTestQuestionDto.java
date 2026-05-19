package diplom.platform.ui.dto;

import java.util.List;
import java.util.UUID;

public record AssignmentTestQuestionDto(
        UUID id,
        String questionText,
        String questionType,
        List<String> options,
        String correctAnswer,
        double points
) {
}

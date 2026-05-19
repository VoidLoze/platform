package diplom.platform.ui.dto;

import java.util.List;
import java.util.UUID;

public record AssignmentTestDto(
        UUID id,
        String title,
        double maxScore,
        List<AssignmentTestQuestionDto> questions
) {
}

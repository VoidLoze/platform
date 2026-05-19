package diplom.platform.ui.dto;

import java.util.List;
import java.util.UUID;

public record GeneratedTestDto(UUID id, double maxScore, List<QuestionDto> questions) {
}

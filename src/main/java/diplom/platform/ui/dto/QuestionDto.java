package diplom.platform.ui.dto;

import java.util.UUID;

public record QuestionDto(UUID id, String text, String difficulty, String correctAnswer) {
}

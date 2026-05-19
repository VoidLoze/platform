package diplom.platform.ui.dto;

import java.util.UUID;

public record AIReviewDto(UUID id, double scoreValue, double maxScore, String summary, String detailedFeedback, String recommendations) {
}

package diplom.platform.aicheck.domain;

import java.util.List;

/**
 * Результат одной попытки проверки одного провайдера. Поля `score` и `recommendations` — по 100-балльной шкале.
 */
public record AiCheckOutcome(
        AiProviderId provider,
        double score,
        double maxScore,
        String summary,
        String detailedFeedback,
        List<String> strengths,
        List<String> issues,
        List<MaterialRecommendation> recommendations,
        String rawModelResponse,
        long latencyMs
) {
    public record MaterialRecommendation(String title, String description, String url, String resourceType) {
    }
}

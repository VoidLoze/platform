package diplom.platform.aicheck.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.aicheck.domain.AiCheckOutcome;
import diplom.platform.aicheck.domain.AiProviderId;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбирает ответ модели в строгий {@link AiCheckOutcome}. Терпим к "грязному" JSON
 * (markdown-блоки, лишний текст), вычленяет первый встретившийся JSON-объект.
 */
public final class AiCheckResponseParser {

    private static final double DEFAULT_MAX_SCORE = 100.0;

    private AiCheckResponseParser() {
    }

    public static AiCheckOutcome parse(AiProviderId provider, String raw, long latencyMs, ObjectMapper mapper) {
        String json = extractJson(raw);
        try {
            JsonNode root = mapper.readTree(json);
            double score = clampScore(root.path("score").asDouble(0));
            String summary = textOr(root, "summary", "Работа проверена");
            String detailed = textOr(root, "detailed_feedback", "");
            List<String> strengths = textArray(root.path("strengths"));
            List<String> issues = textArray(root.path("issues"));
            List<AiCheckOutcome.MaterialRecommendation> recs = new ArrayList<>();
            JsonNode recNode = root.path("recommendations");
            if (recNode.isArray()) {
                for (JsonNode r : recNode) {
                    recs.add(new AiCheckOutcome.MaterialRecommendation(
                            textOr(r, "title", ""),
                            textOr(r, "description", ""),
                            textOr(r, "url", ""),
                            textOr(r, "resource_type", "article")));
                }
            }
            return new AiCheckOutcome(provider, score, DEFAULT_MAX_SCORE, summary, detailed, strengths, issues, recs, raw, latencyMs);
        } catch (Exception e) {
            return new AiCheckOutcome(
                    provider,
                    50.0,
                    DEFAULT_MAX_SCORE,
                    "Модель ответила в свободной форме",
                    raw == null ? "" : raw.trim(),
                    List.of(),
                    List.of(),
                    List.of(),
                    raw,
                    latencyMs);
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private static double clampScore(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        if (v < 0) {
            return 0;
        }
        return Math.min(v, DEFAULT_MAX_SCORE);
    }

    private static String textOr(JsonNode node, String field, String def) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return def;
        }
        return v.asText(def);
    }

    private static List<String> textArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode it : node) {
                String s = it.asText("");
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}

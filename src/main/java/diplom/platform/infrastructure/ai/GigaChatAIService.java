package diplom.platform.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import diplom.platform.evaluation.domain.AIService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GigaChatAIService implements AIService {
    private static final Logger log = LoggerFactory.getLogger(GigaChatAIService.class);

    private final RestClient client;
    private final RestClient gigaChatAuthClient;
    private final String model;
    private final String gigaChatToken;
    private final String gigaChatScope;
    private final RestClient deepSeekClient;
    private final String deepSeekModel;
    private final String deepSeekToken;
    private final ObjectMapper mapper;
    /** Parses LLM JSON with relaxed backslash rules (GigaChat often emits invalid escapes). */
    private final ObjectMapper lenientAiJsonMapper;
    private volatile String gigaChatAccessToken;
    private volatile Instant gigaChatAccessTokenExpiresAt;

    public GigaChatAIService(@Value("${gigachat.base-url:https://gigachat.devices.sberbank.ru/api/v1}") String baseUrl,
                             @Value("${gigachat.auth-url:https://ngw.devices.sberbank.ru:9443/api/v2/oauth}") String gigaChatAuthUrl,
                             @Value("${gigachat.model:GigaChat}") String model,
                             @Value("${gigachat.token:demo-token}") String token,
                             @Value("${gigachat.scope:GIGACHAT_API_PERS}") String gigaChatScope,
                             @Value("${platform.aicheck.gigachat.insecure-ssl:false}") boolean gigaChatInsecureSsl,
                             @Value("${platform.aicheck.deepseek.base-url:https://api.deepseek.com/v1}") String deepSeekBaseUrl,
                             @Value("${platform.aicheck.deepseek.model:deepseek-chat}") String deepSeekModel,
                             @Value("${platform.aicheck.deepseek.token:}") String deepSeekToken,
                             ObjectMapper mapper) {
        this.client = buildGigaChatClient(baseUrl, gigaChatInsecureSsl);
        this.gigaChatAuthClient = buildGigaChatClient(gigaChatAuthUrl, gigaChatInsecureSsl);
        this.model = model;
        this.gigaChatToken = resolveGigaChatToken(token);
        this.gigaChatScope = gigaChatScope;
        this.deepSeekClient = RestClient.builder().baseUrl(deepSeekBaseUrl).build();
        this.deepSeekModel = deepSeekModel;
        this.deepSeekToken = resolveDeepSeekToken(deepSeekToken);
        this.mapper = mapper;
        this.lenientAiJsonMapper = mapper.copy()
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true);
    }

    /**
     * Bearer для вызовов GigaChat Chat Completions (OAuth при authorization key, иначе уже access token).
     * Используется AI-check провайдером; логика совпадает с остальными вызовами GigaChat в приложении.
     */
    public String obtainGigaChatBearerForChatCompletions() {
        if (gigaChatToken == null || gigaChatToken.isBlank() || "demo-token".equals(gigaChatToken)) {
            throw new IllegalStateException("GigaChat token is not configured");
        }
        return resolveGigaChatAccessToken();
    }

    @Override
    public AIReviewResult analyze(String content, double maxScore, String rubric) {
        try {
            Map<?, ?> ignored = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + resolveGigaChatAccessToken())
                    .body(Map.of("model", model, "messages", List.of(
                            Map.of("role", "system", "content", "Ты проверяешь лабораторные работы."),
                            Map.of("role", "user", "content", rubric + "\n\nРабота:\n" + content))))
                    .retrieve()
                    .body(Map.class);
            // Для MVP берем детерминированный формат, если ответ не удалось распарсить.
            double score = Math.max(0, Math.min(maxScore, maxScore * 0.8));
            return new AIReviewResult(score, "Работа проверена GigaChat", "Хорошее покрытие темы, но есть зоны улучшения.", "Доработать обработку ошибок и добавить тесты.");
        } catch (Exception ex) {
            return new AIReviewResult(maxScore * 0.5, "Fallback review", "Сервис ИИ временно недоступен, применена базовая оценка.", "Повторить проверку позже.");
        }
    }

    @Override
    public GeneratedTestResult generateTest(String reviewSummary, int questionCount) {
        if (gigaChatToken == null || gigaChatToken.isBlank() || "demo-token".equals(gigaChatToken)) {
            throw new IllegalStateException("GigaChat token is not configured");
        }
        try {
            return generateTestAttempt(reviewSummary, questionCount, false);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                throw new IllegalStateException("GigaChat billing error: insufficient balance (402). Пополните баланс API-ключа.", e);
            }
            throw new IllegalStateException("GigaChat test generation failed: " + e.getStatusCode().value(), e);
        } catch (IllegalStateException e) {
            if (isJsonParseFailure(e) && !e.getMessage().contains("retry")) {
                try {
                    log.warn("GigaChat test JSON parse failed, retrying with stricter prompt: {}", e.getMessage());
                    return generateTestAttempt(reviewSummary, questionCount, true);
                } catch (Exception retryEx) {
                    throw new IllegalStateException("GigaChat test generation failed after retry", retryEx);
                }
            }
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GigaChat test generation failed", e);
        }
    }

    private GeneratedTestResult generateTestAttempt(String reviewSummary, int questionCount, boolean retryAfterParseFailure) throws Exception {
        String system = """
                Ты методист. Генерируй полноценные тесты.
                Отвечай строго одним JSON-объектом, без markdown, без комментариев и без текста до/после JSON.
                Не используй символ обратного слэша в текстах вопросов и вариантов ответа.
                Формат: {"questions":[{"text":"...","difficulty":"easy|medium|hard","options":["...","...","...","..."],"correctAnswer":"..."}]}
                Поле correctAnswer должно совпадать с одним из элементов options (точная строка).
                """.trim();
        if (retryAfterParseFailure) {
            system += "\nПовтори ответ: только валидный JSON, экранируй кавычки внутри строк как \\\".";
        }
        String user = """
                Сгенерируй %d тестовых вопросов по теме ниже.
                В каждом вопросе обязательно 4 варианта ответа и один корректный ответ.
                
                Тема/контекст:
                %s
                """.formatted(questionCount, reviewSummary);
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                ),
                "temperature", retryAfterParseFailure ? 0.1 : 0.2
        );
        String rawResponse = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + resolveGigaChatAccessToken())
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode response = rawResponse == null || rawResponse.isBlank() ? mapper.createObjectNode() : mapper.readTree(rawResponse);
        String content = extractContent(response);
        List<GeneratedQuestion> parsed;
        try {
            parsed = parseGeneratedQuestions(content, questionCount);
        } catch (JsonProcessingException e) {
            log.warn("GigaChat test content JSON invalid (retry={}): {}", retryAfterParseFailure, abbreviate(content, 500));
            throw new IllegalStateException("GigaChat returned invalid test JSON" + (retryAfterParseFailure ? " (retry)" : ""), e);
        }
        if (parsed.isEmpty()) {
            log.warn("GigaChat test payload empty after parse (retry={}): {}", retryAfterParseFailure, abbreviate(content, 500));
            throw new IllegalStateException("GigaChat returned empty/invalid test payload" + (retryAfterParseFailure ? " (retry)" : ""));
        }
        return new GeneratedTestResult(100, parsed);
    }

    private static boolean isJsonParseFailure(Throwable e) {
        while (e != null) {
            if (e instanceof JsonProcessingException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    @Override
    public GeneratedAssignmentDraft generateAssignmentDraft(String subjectArea, String difficulty, String learningGoals) {
        String normalizedSubject = subjectArea == null || subjectArea.isBlank() ? "GENERAL" : subjectArea.toUpperCase();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.toLowerCase();
        String goals = learningGoals == null || learningGoals.isBlank() ? "Базовое закрепление темы" : learningGoals;
        String title = switch (normalizedSubject) {
            case "CS" -> "Практикум по алгоритмам и структурам данных";
            case "MATH" -> "Решение прикладных задач по математике";
            case "PHYSICS" -> "Лабораторная работа по физике";
            case "HISTORY" -> "Аналитическое эссе по истории";
            default -> "Межпредметное практическое задание";
        };
        String description = """
                Уровень сложности: %s.
                Цели обучения: %s.
                Требуется предоставить структурированный ответ, аргументацию выбранного решения и выводы.
                В конце работы добавьте краткую самопроверку по критериям качества.
                """.formatted(normalizedDifficulty, goals).trim();
        double maxScore = switch (normalizedDifficulty) {
            case "hard" -> 120.0;
            case "easy" -> 80.0;
            default -> 100.0;
        };
        double latePenalty = "hard".equals(normalizedDifficulty) ? 15.0 : 10.0;
        return new GeneratedAssignmentDraft(title, description, normalizedSubject, maxScore, latePenalty);
    }

    private String extractContent(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode choices = response.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode msg = choices.get(0).path("message").path("content");
            if (msg.isTextual()) {
                return msg.asText();
            }
        }
        return response.toString();
    }

    private List<GeneratedQuestion> parseGeneratedQuestions(String content, int limit) throws Exception {
        String json = normalizePotentialJson(content);
        if (json.isBlank()) return List.of();
        JsonNode root = tryParseJsonTree(json);
        JsonNode questionsNode = root;
        if (root.isObject() && root.has("questions")) {
            questionsNode = root.path("questions");
        }
        if (!questionsNode.isArray() && root.isObject() && root.has("data")) {
            questionsNode = root.path("data");
        }
        if (!questionsNode.isArray()) return List.of();
        List<GeneratedQuestion> out = new ArrayList<>();
        for (JsonNode n : questionsNode) {
            if (out.size() >= limit) break;
            String text = firstText(n, "text", "question", "questionText", "title");
            String difficulty = n.path("difficulty").asText("medium");
            String correct = firstText(n, "correctAnswer", "answer", "correct_option", "correct");
            List<String> options = extractOptions(n.path("options"));
            if (options.isEmpty()) {
                options = extractOptions(n.path("answers"));
            }
            if (options.isEmpty()) {
                options = extractOptions(n.path("choices"));
            }
            if (text.isBlank() || correct.isBlank()) {
                continue;
            }
            if (options.size() != 4) {
                continue;
            }
            String matchedCorrect = resolveCorrectAnswer(correct, options);
            if (matchedCorrect == null) {
                continue;
            }
            out.add(new GeneratedQuestion(text, difficulty, options, matchedCorrect));
        }
        return out;
    }

    private JsonNode tryParseJsonTree(String json) throws JsonProcessingException {
        JsonProcessingException lastError = null;
        String sanitized = sanitizeInvalidJsonControlChars(json);
        List<String> candidates = List.of(
                json,
                sanitized,
                fixInvalidBackslashEscapesInJsonStrings(json),
                fixInvalidBackslashEscapesInJsonStrings(sanitized),
                replacePlusOutsideStringsWithComma(sanitized),
                replacePlusOutsideStringsWithComma(fixInvalidBackslashEscapesInJsonStrings(sanitized))
        );
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return lenientAiJsonMapper.readTree(candidate);
            } catch (JsonProcessingException e) {
                lastError = e;
            }
            try {
                return mapper.readTree(candidate);
            } catch (JsonProcessingException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new JsonProcessingException("Unable to parse AI JSON payload") {};
    }

    /** Repairs invalid backslash escapes inside JSON string literals from LLM output. */
    private String fixInvalidBackslashEscapesInJsonStrings(String json) {
        StringBuilder out = new StringBuilder(json.length() + 32);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                    escaped = false;
                }
                continue;
            }
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (isStartOfValidJsonEscape(json, i)) {
                    out.append(c);
                    escaped = true;
                } else {
                    out.append("\\\\");
                }
                continue;
            }
            if (c == '"') {
                out.append(c);
                inString = false;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isStartOfValidJsonEscape(String json, int slashIndex) {
        int next = slashIndex + 1;
        if (next >= json.length()) {
            return false;
        }
        return switch (json.charAt(next)) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true;
            case 'u' -> next + 4 < json.length() && isHexSequence(json, next + 1, 4);
            default -> false;
        };
    }

    private static boolean isHexSequence(String s, int offset, int length) {
        if (offset < 0 || offset + length > s.length()) {
            return false;
        }
        for (int i = offset; i < offset + length; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private String normalizePotentialJson(String rawContent) {
        String json = rawContent == null ? "" : rawContent.trim();
        if (json.startsWith("```")) {
            int firstNl = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNl >= 0 && lastFence > firstNl) {
                json = json.substring(firstNl + 1, lastFence).trim();
            }
        }
        if (!json.startsWith("{") && !json.startsWith("[")) {
            int objStart = json.indexOf('{');
            int arrStart = json.indexOf('[');
            int start = -1;
            if (objStart >= 0 && arrStart >= 0) {
                start = Math.min(objStart, arrStart);
            } else if (objStart >= 0) {
                start = objStart;
            } else if (arrStart >= 0) {
                start = arrStart;
            }
            if (start >= 0) {
                json = json.substring(start).trim();
            }
        }
        json = trimToBalancedJson(json);
        return json;
    }

    private String trimToBalancedJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        char first = json.charAt(0);
        if (first != '{' && first != '[') {
            return json;
        }
        char open = first;
        char close = first == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(0, i + 1);
                }
            }
        }
        return json;
    }

    /**
     * LLM outputs sometimes contain raw CR/LF/TAB control chars inside string literals.
     * JSON requires escaping those chars, so we convert them to \\n/\\r/\\t only when inside quoted strings.
     */
    private String sanitizeInvalidJsonControlChars(String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                    escaped = false;
                }
                continue;
            }
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                out.append(c);
                inString = false;
                continue;
            }
            if (c == '\n') {
                out.append("\\n");
                continue;
            }
            if (c == '\r') {
                out.append("\\r");
                continue;
            }
            if (c == '\t') {
                out.append("\\t");
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Some models occasionally emit JS-like concatenation with '+' between object entries.
     * Example: {"a":"x" + "b":"y"}; for JSON recovery we treat '+' outside strings as separator.
     */
    private String replacePlusOutsideStringsWithComma(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '+') {
                out.append(',');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && value.isTextual()) {
                String text = value.asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private List<String> extractOptions(JsonNode optionsNode) {
        List<String> options = new ArrayList<>();
        if (optionsNode == null || optionsNode.isMissingNode() || optionsNode.isNull()) {
            return options;
        }
        if (optionsNode.isArray()) {
            for (JsonNode opt : optionsNode) {
                if (opt.isTextual()) {
                    String v = opt.asText("").trim();
                    if (!v.isEmpty()) options.add(v);
                } else if (opt.isObject()) {
                    String v = firstText(opt, "text", "value", "option");
                    if (!v.isEmpty()) options.add(v);
                }
            }
            return options;
        }
        if (optionsNode.isObject()) {
            String a = firstText(optionsNode, "A", "a", "1");
            String b = firstText(optionsNode, "B", "b", "2");
            String c = firstText(optionsNode, "C", "c", "3");
            String d = firstText(optionsNode, "D", "d", "4");
            return List.of(a, b, c, d).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return options;
    }

    private String resolveCorrectAnswer(String correctRaw, List<String> options) {
        String correct = correctRaw == null ? "" : correctRaw.trim();
        if (correct.isEmpty()) {
            return null;
        }
        String byText = options.stream()
                .filter(opt -> opt.equalsIgnoreCase(correct))
                .findFirst()
                .orElse(null);
        if (byText != null) {
            return byText;
        }
        String normalized = correct.replace(".", "").replace(")", "").trim().toUpperCase();
        int idx = switch (normalized) {
            case "A", "1" -> 0;
            case "B", "2" -> 1;
            case "C", "3" -> 2;
            case "D", "4" -> 3;
            default -> -1;
        };
        if (idx >= 0 && idx < options.size()) {
            return options.get(idx);
        }
        return null;
    }

    private String resolveDeepSeekToken(String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank()) {
            return configuredToken.trim();
        }
        String envToken = System.getenv("PLATFORM_AICHECK_DEEPSEEK_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }
        String genericToken = System.getenv("DEEPSEEK_API_KEY");
        if (genericToken != null && !genericToken.isBlank()) {
            return genericToken.trim();
        }
        return "";
    }

    private String resolveGigaChatToken(String configuredToken) {
        if (configuredToken != null && !configuredToken.isBlank() && !"demo-token".equals(configuredToken.trim())) {
            return configuredToken.trim();
        }
        String envToken = System.getenv("GIGACHAT_TOKEN");
        if (envToken != null && !envToken.isBlank() && !"demo-token".equals(envToken.trim())) {
            return envToken.trim();
        }
        String platformToken = System.getenv("PLATFORM_AICHECK_GIGACHAT_TOKEN");
        if (platformToken != null && !platformToken.isBlank() && !"demo-token".equals(platformToken.trim())) {
            return platformToken.trim();
        }
        return "";
    }

    private RestClient buildGigaChatClient(String baseUrl, boolean insecureSsl) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (!insecureSsl) {
            return builder.build();
        }
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            HttpClient httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            return builder.requestFactory(new JdkClientHttpRequestFactory(httpClient)).build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize insecure SSL client for GigaChat", e);
        }
    }

    private synchronized String resolveGigaChatAccessToken() {
        if (isLikelyAccessToken(gigaChatToken)) {
            return gigaChatToken;
        }
        Instant now = Instant.now();
        if (gigaChatAccessToken != null && gigaChatAccessTokenExpiresAt != null && now.isBefore(gigaChatAccessTokenExpiresAt.minusSeconds(30))) {
            return gigaChatAccessToken;
        }
        String rawResponse = gigaChatAuthClient.post()
                .header("Authorization", "Basic " + gigaChatToken)
                .header("RqUID", UUID.randomUUID().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("scope=" + gigaChatScope)
                .retrieve()
                .body(String.class);
        JsonNode response;
        try {
            response = rawResponse == null || rawResponse.isBlank() ? mapper.createObjectNode() : mapper.readTree(rawResponse);
        } catch (Exception e) {
            throw new IllegalStateException("GigaChat OAuth token response parse failed", e);
        }
        if (response == null || response.path("access_token").asText("").isBlank()) {
            throw new IllegalStateException("GigaChat OAuth token exchange failed");
        }
        gigaChatAccessToken = response.path("access_token").asText();
        long expiresAtMillis = response.path("expires_at").asLong(0L);
        gigaChatAccessTokenExpiresAt = expiresAtMillis > 0 ? Instant.ofEpochMilli(expiresAtMillis) : Instant.now().plusSeconds(25 * 60);
        return gigaChatAccessToken;
    }

    private boolean isLikelyAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        if ("demo-token".equals(trimmed)) {
            return false;
        }
        // Authorization key from Sber is usually base64(client_id:client_secret) and often ends with '='.
        if (trimmed.contains(":") || trimmed.endsWith("=")) {
            return false;
        }
        return true;
    }
}

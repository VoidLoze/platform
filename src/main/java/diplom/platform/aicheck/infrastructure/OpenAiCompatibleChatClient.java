package diplom.platform.aicheck.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Тонкий клиент для OpenAI-совместимых чат-моделей (DeepSeek, Qwen и пр.).
 * Поддерживает текст и мультимодальный ввод (изображение base64) через формат content-array.
 */
public class OpenAiCompatibleChatClient {

    private final RestClient client;
    private final String model;
    private final String token;
    private final ObjectMapper mapper;

    public OpenAiCompatibleChatClient(String baseUrl, String token, String model, ObjectMapper mapper) {
        this(baseUrl, token, model, mapper, false);
    }

    public OpenAiCompatibleChatClient(String baseUrl, String token, String model, ObjectMapper mapper, boolean insecureSsl) {
        this.client = buildClient(baseUrl, insecureSsl);
        this.token = token;
        this.model = model;
        this.mapper = mapper;
    }

    public ChatResponse chat(String systemPrompt, String userPrompt, String imageBase64, String imageMimeType, Duration timeout) {
        return chat(systemPrompt, userPrompt, imageBase64, imageMimeType, timeout, null);
    }

    /**
     * @param bearerTokenOverride if non-blank, used as Bearer instead of constructor {@link #token} (for OAuth access tokens).
     */
    public ChatResponse chat(String systemPrompt, String userPrompt, String imageBase64, String imageMimeType, Duration timeout,
                             String bearerTokenOverride) {
        String bearer = (bearerTokenOverride != null && !bearerTokenOverride.isBlank()) ? bearerTokenOverride : token;
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (imageBase64 != null && !imageBase64.isBlank()) {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", userPrompt));
            String mime = imageMimeType == null || imageMimeType.isBlank() ? "image/png" : imageMimeType;
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:" + mime + ";base64," + imageBase64)));
            messages.add(Map.of("role", "user", "content", content));
        } else {
            messages.add(Map.of("role", "user", "content", userPrompt));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.2);

        long start = System.currentTimeMillis();
        try {
            // Spring Boot 4 / Jackson 3: RestClient cannot deserialize JsonNode as a simple type here.
            String raw = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            long latency = System.currentTimeMillis() - start;
            JsonNode response = (raw == null || raw.isBlank()) ? mapper.createObjectNode() : mapper.readTree(raw);
            String content = extractContent(response);
            return new ChatResponse(content, latency, null);
        } catch (Exception e) {
            return new ChatResponse(null, System.currentTimeMillis() - start, e);
        }
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
            if (msg.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : msg) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) {
                        sb.append(text);
                    }
                }
                return sb.toString();
            }
        }
        return response.toString();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public record ChatResponse(String content, long latencyMs, Exception error) {
    }

    private RestClient buildClient(String baseUrl, boolean insecureSsl) {
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
            throw new IllegalStateException("Unable to initialize insecure SSL OpenAI-compatible client", e);
        }
    }
}

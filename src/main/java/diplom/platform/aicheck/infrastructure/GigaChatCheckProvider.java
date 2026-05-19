package diplom.platform.aicheck.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.aicheck.domain.AiCheckOutcome;
import diplom.platform.aicheck.domain.AiCheckProvider;
import diplom.platform.aicheck.domain.AiCheckRequest;
import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.aicheck.domain.AiProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import diplom.platform.infrastructure.ai.GigaChatAIService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Провайдер на базе GigaChat. API совместимо с OpenAI Chat Completions.
 * Включается флагом {@code platform.aicheck.gigachat.enabled} (по умолчанию true).
 */
@Component
public class GigaChatCheckProvider implements AiCheckProvider {

    private static final Logger log = LoggerFactory.getLogger(GigaChatCheckProvider.class);

    private final OpenAiCompatibleChatClient client;
    private final GigaChatAIService gigaChatAuth;
    private final boolean enabled;

    public GigaChatCheckProvider(
            ObjectMapper mapper,
            GigaChatAIService gigaChatAuth,
            @Value("${gigachat.base-url:https://gigachat.devices.sberbank.ru/api/v1}") String baseUrl,
            @Value("${gigachat.model:GigaChat}") String model,
            @Value("${gigachat.token:demo-token}") String token,
            @Value("${platform.aicheck.gigachat.insecure-ssl:false}") boolean insecureSsl,
            @Value("${platform.aicheck.gigachat.enabled:true}") boolean enabled) {
        this.gigaChatAuth = gigaChatAuth;
        // Токен конфигурации — authorization key; для API нужен OAuth access token из GigaChatAIService.
        this.client = new OpenAiCompatibleChatClient(baseUrl, token, model, mapper, insecureSsl);
        this.enabled = enabled;
    }

    @Override
    public AiProviderId id() {
        return AiProviderId.GIGACHAT;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean supports(AiCheckSourceType sourceType) {
        return sourceType != null;
    }

    @Override
    public AiCheckOutcome check(AiCheckRequest request) {
        String system = AiCheckPrompts.systemPrompt(request.subject(), request.sourceType());
        String user = AiCheckPrompts.userPrompt(request);
        String bearer;
        try {
            bearer = gigaChatAuth.obtainGigaChatBearerForChatCompletions();
        } catch (Exception e) {
            log.warn("GigaChat недоступен (OAuth): {}", e.getMessage());
            return new AiCheckOutcome(id(), 0, 100, "GigaChat недоступен",
                    "Не удалось получить access token: " + e.getMessage(),
                    List.of(), List.of(), List.of(), null, 0L);
        }
        OpenAiCompatibleChatClient.ChatResponse resp = client.chat(
                system, user, request.imageBase64(), request.imageMimeType(), Duration.ofSeconds(60), bearer);
        if (resp.error() != null) {
            log.warn("GigaChat провайдер недоступен: {}", resp.error().getMessage());
            return new AiCheckOutcome(id(), 0, 100, "GigaChat недоступен", resp.error().getMessage(),
                    List.of(), List.of(), List.of(), null, resp.latencyMs());
        }
        return AiCheckResponseParser.parse(id(), resp.content(), resp.latencyMs(), client.mapper());
    }
}

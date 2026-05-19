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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Провайдер Qwen (DashScope OpenAI-совместимый endpoint). Поддерживает мультимодальные модели (Qwen-VL).
 */
@Component
public class QwenCheckProvider implements AiCheckProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenCheckProvider.class);

    private final OpenAiCompatibleChatClient client;
    private final boolean enabled;

    public QwenCheckProvider(
            ObjectMapper mapper,
            @Value("${platform.aicheck.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${platform.aicheck.qwen.model:qwen-plus}") String model,
            @Value("${platform.aicheck.qwen.token:}") String token,
            @Value("${platform.aicheck.qwen.enabled:true}") boolean enabled) {
        this.client = new OpenAiCompatibleChatClient(baseUrl, token, model, mapper);
        this.enabled = enabled && token != null && !token.isBlank();
    }

    @Override
    public AiProviderId id() {
        return AiProviderId.QWEN;
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
        OpenAiCompatibleChatClient.ChatResponse resp = client.chat(
                system, user, request.imageBase64(), request.imageMimeType(), Duration.ofSeconds(60));
        if (resp.error() != null) {
            log.warn("Qwen провайдер недоступен: {}", resp.error().getMessage());
            return new AiCheckOutcome(id(), 0, 100, "Qwen недоступен", resp.error().getMessage(),
                    List.of(), List.of(), List.of(), null, resp.latencyMs());
        }
        return AiCheckResponseParser.parse(id(), resp.content(), resp.latencyMs(), client.mapper());
    }
}

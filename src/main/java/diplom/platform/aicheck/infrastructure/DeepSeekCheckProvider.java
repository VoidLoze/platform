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
import java.util.Set;

/**
 * Провайдер DeepSeek (OpenAI-совместимый API). Особо полезен на CS/MATH; изображения по умолчанию не поддерживаются
 * на чат-моделях DeepSeek, поэтому при image-входе провайдер деактивируется в роутере.
 */
@Component
public class DeepSeekCheckProvider implements AiCheckProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekCheckProvider.class);

    private final OpenAiCompatibleChatClient client;
    private final boolean enabled;

    public DeepSeekCheckProvider(
            ObjectMapper mapper,
            @Value("${platform.aicheck.deepseek.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${platform.aicheck.deepseek.model:deepseek-chat}") String model,
            @Value("${platform.aicheck.deepseek.token:}") String token,
            @Value("${platform.aicheck.deepseek.enabled:true}") boolean enabled) {
        this.client = new OpenAiCompatibleChatClient(baseUrl, token, model, mapper);
        this.enabled = enabled && token != null && !token.isBlank();
    }

    @Override
    public AiProviderId id() {
        return AiProviderId.DEEPSEEK;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean supports(AiCheckSourceType sourceType) {
        return !Set.of(AiCheckSourceType.IMAGE).contains(sourceType);
    }

    @Override
    public AiCheckOutcome check(AiCheckRequest request) {
        String system = AiCheckPrompts.systemPrompt(request.subject(), request.sourceType());
        String user = AiCheckPrompts.userPrompt(request);
        OpenAiCompatibleChatClient.ChatResponse resp = client.chat(
                system, user, null, null, Duration.ofSeconds(60));
        if (resp.error() != null) {
            log.warn("DeepSeek провайдер недоступен: {}", resp.error().getMessage());
            return new AiCheckOutcome(id(), 0, 100, "DeepSeek недоступен", resp.error().getMessage(),
                    List.of(), List.of(), List.of(), null, resp.latencyMs());
        }
        return AiCheckResponseParser.parse(id(), resp.content(), resp.latencyMs(), client.mapper());
    }
}

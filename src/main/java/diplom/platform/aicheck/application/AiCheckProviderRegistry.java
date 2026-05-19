package diplom.platform.aicheck.application;

import diplom.platform.aicheck.domain.AiCheckProvider;
import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.aicheck.domain.AiProviderId;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Реестр доступных AI-провайдеров: сводит все {@link AiCheckProvider} бины и отдаёт по идентификатору.
 */
@Component
public class AiCheckProviderRegistry {

    private final Map<AiProviderId, AiCheckProvider> providers = new EnumMap<>(AiProviderId.class);

    public AiCheckProviderRegistry(List<AiCheckProvider> beans) {
        for (AiCheckProvider p : beans) {
            providers.put(p.id(), p);
        }
    }

    public Optional<AiCheckProvider> find(AiProviderId id) {
        return Optional.ofNullable(providers.get(id));
    }

    public boolean isAvailable(AiProviderId id, AiCheckSourceType sourceType) {
        AiCheckProvider p = providers.get(id);
        return p != null && p.enabled() && p.supports(sourceType);
    }
}

package diplom.platform.aicheck.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.aicheck.domain.AiCheckSubject;
import diplom.platform.aicheck.domain.AiProviderId;
import diplom.platform.aicheck.infrastructure.AiCheckSettingsEntity;
import diplom.platform.aicheck.infrastructure.AiCheckSettingsJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Хранение и применение глобальной политики выбора AI-провайдеров: дефолтный провайдер,
 * порядок fallback и опциональный per-subject override.
 * Политика применяется на старте каждого AI-job.
 */
@Service
public class AiCheckPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AiCheckPolicyService.class);
    private static final int SETTINGS_ID = 1;

    private final AiCheckSettingsJpaRepository settings;
    private final ObjectMapper mapper;

    public AiCheckPolicyService(AiCheckSettingsJpaRepository settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    @Transactional
    public AiCheckSettingsEntity getOrCreate() {
        return settings.findById(SETTINGS_ID).orElseGet(() -> {
            AiCheckSettingsEntity e = new AiCheckSettingsEntity();
            e.setId(SETTINGS_ID);
            e.setDefaultProvider(AiProviderId.GIGACHAT.name());
            e.setFallbackChain(String.join(",", AiProviderId.GIGACHAT.name(), AiProviderId.QWEN.name(), AiProviderId.DEEPSEEK.name()));
            e.setSubjectPolicyJson("{}");
            e.setUpdatedAt(OffsetDateTime.now());
            return settings.save(e);
        });
    }

    @Transactional
    public AiCheckSettingsEntity update(String defaultProvider, List<String> fallbackChain, Map<String, String> subjectPolicy) {
        AiCheckSettingsEntity e = getOrCreate();
        AiProviderId def = AiProviderId.parse(defaultProvider);
        if (def != null) {
            e.setDefaultProvider(def.name());
        }
        if (fallbackChain != null && !fallbackChain.isEmpty()) {
            List<String> normalized = new ArrayList<>();
            for (String raw : fallbackChain) {
                AiProviderId id = AiProviderId.parse(raw);
                if (id != null && !normalized.contains(id.name())) {
                    normalized.add(id.name());
                }
            }
            if (!normalized.isEmpty()) {
                e.setFallbackChain(String.join(",", normalized));
            }
        }
        if (subjectPolicy != null) {
            try {
                Map<String, String> normalized = new LinkedHashMap<>();
                subjectPolicy.forEach((k, v) -> {
                    AiCheckSubject subj = AiCheckSubject.parse(k);
                    AiProviderId id = AiProviderId.parse(v);
                    if (subj != null && id != null) {
                        normalized.put(subj.name(), id.name());
                    }
                });
                e.setSubjectPolicyJson(mapper.writeValueAsString(normalized));
            } catch (Exception ex) {
                log.warn("Не удалось сериализовать subject policy", ex);
            }
        }
        e.setUpdatedAt(OffsetDateTime.now());
        return settings.save(e);
    }

    /**
     * Цепочка провайдеров: первый — preferred (override на subject либо default), затем fallback chain без дубликатов.
     */
    public List<AiProviderId> resolveProviderChain(AiCheckSubject subject, AiProviderId override) {
        AiCheckSettingsEntity e = getOrCreate();
        List<AiProviderId> chain = new ArrayList<>();
        AiProviderId preferred = override;
        if (preferred == null) {
            preferred = subjectPolicyProvider(e, subject);
        }
        if (preferred == null) {
            preferred = AiProviderId.parse(e.getDefaultProvider());
        }
        if (preferred != null) {
            chain.add(preferred);
        }
        for (String raw : e.getFallbackChain().split(",")) {
            AiProviderId id = AiProviderId.parse(raw);
            if (id != null && !chain.contains(id)) {
                chain.add(id);
            }
        }
        if (chain.isEmpty()) {
            chain.addAll(Arrays.asList(AiProviderId.GIGACHAT, AiProviderId.QWEN, AiProviderId.DEEPSEEK));
        }
        return chain;
    }

    public Map<String, String> subjectPolicy() {
        AiCheckSettingsEntity e = getOrCreate();
        if (e.getSubjectPolicyJson() == null || e.getSubjectPolicyJson().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(e.getSubjectPolicyJson(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private AiProviderId subjectPolicyProvider(AiCheckSettingsEntity e, AiCheckSubject subject) {
        if (subject == null) {
            return null;
        }
        String json = e.getSubjectPolicyJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, String> policy = mapper.readValue(json, new TypeReference<>() {
            });
            if (policy.isEmpty()) {
                return null;
            }
            String raw = policy.getOrDefault(subject.name(), policy.get(subject.name().toLowerCase(Locale.ROOT)));
            return AiProviderId.parse(raw);
        } catch (Exception ex) {
            return null;
        }
    }
}

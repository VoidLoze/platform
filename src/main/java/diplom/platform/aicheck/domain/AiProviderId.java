package diplom.platform.aicheck.domain;

/**
 * Идентификаторы AI-провайдеров для умной проверки работ.
 * Стабильные строковые имена используются в БД и в админ-настройках.
 */
public enum AiProviderId {
    GIGACHAT,
    DEEPSEEK,
    QWEN;

    public static AiProviderId parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return AiProviderId.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

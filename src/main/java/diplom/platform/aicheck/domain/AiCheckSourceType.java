package diplom.platform.aicheck.domain;

/**
 * Тип источника проверяемой работы. Помогает правильно подготовить контент перед отправкой в модель:
 * текст, фото с задачей, архив с проектом, репозиторий Git, либо файл общего назначения.
 */
public enum AiCheckSourceType {
    TEXT,
    IMAGE,
    CODE_ARCHIVE,
    CODE_GIT,
    DOCUMENT;

    public static AiCheckSourceType parse(String raw) {
        if (raw == null) {
            return TEXT;
        }
        try {
            return AiCheckSourceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }
}

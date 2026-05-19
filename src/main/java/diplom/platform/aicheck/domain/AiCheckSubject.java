package diplom.platform.aicheck.domain;

import java.util.Locale;

/**
 * Предметная область проверки. Используется для подбора провайдера и формирования
 * предметно-ориентированной системной инструкции для модели.
 */
public enum AiCheckSubject {
    MATH,
    PHYSICS,
    CS,
    HISTORY,
    LITERATURE,
    LANGUAGE,
    BIOLOGY,
    CHEMISTRY,
    GENERAL;

    public static AiCheckSubject parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        try {
            return AiCheckSubject.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (looksLikeProgrammingDiscipline(raw.trim())) {
                return CS;
            }
            return GENERAL;
        }
    }

    private static boolean looksLikeProgrammingDiscipline(String label) {
        String low = label.toLowerCase(Locale.ROOT);
        return low.contains("информатик")
                || low.contains("программ")
                || low.contains("разработ")
                || low.contains("компьют")
                || low.contains("кибер")
                || low.contains("computer science")
                || low.contains("software")
                || low.contains("java")
                || low.contains("python")
                || low.contains("devops")
                || low.contains("it-")
                || low.startsWith("it ");
    }
}

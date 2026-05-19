package diplom.platform.aicheck.application;

import diplom.platform.aicheck.domain.AiCheckSourceType;
import org.springframework.stereotype.Component;

/**
 * Определяет тип источника и объединяет текстовую часть с файлом для проверки сданной работы.
 * Допускается только текст, только файл или оба вместе.
 */
@Component
public class AiCheckLabWorkSubmissionResolver {

    private final AiCheckSourcePreparator sourcePreparator;

    public AiCheckLabWorkSubmissionResolver(AiCheckSourcePreparator sourcePreparator) {
        this.sourcePreparator = sourcePreparator;
    }

    public record ResolvedSubmission(
            AiCheckSourceType sourceType,
            String studentText,
            String attachmentFileKey
    ) {
    }

    public ResolvedSubmission resolve(String rawText, String attachmentKey) {
        String normalizedText = normalizeStudentText(rawText);
        boolean hasText = normalizedText != null;
        boolean hasAttachment = attachmentKey != null && !attachmentKey.isBlank();
        if (!hasText && !hasAttachment) {
            throw new IllegalArgumentException("Нет текста и файла для ИИ-проверки");
        }
        if (!hasAttachment) {
            return new ResolvedSubmission(AiCheckSourceType.TEXT, normalizedText, null);
        }
        String key = attachmentKey.trim();
        String lower = key.toLowerCase();
        if (isArchive(lower)) {
            return new ResolvedSubmission(
                    AiCheckSourceType.CODE_ARCHIVE,
                    hasText ? normalizedText : null,
                    key
            );
        }
        if (isImage(lower)) {
            return new ResolvedSubmission(
                    AiCheckSourceType.IMAGE,
                    hasText ? normalizedText : null,
                    key
            );
        }
        if (isSingleCodeFile(lower)) {
            String fileBody = sourcePreparator.readAttachmentAsText(key).trim();
            return new ResolvedSubmission(AiCheckSourceType.TEXT, fileBody, key);
        }
        return new ResolvedSubmission(
                AiCheckSourceType.DOCUMENT,
                hasText ? normalizedText : null,
                key
        );
    }

    static String normalizeStudentText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String t = rawText.trim();
        if (isSubmissionPlaceholder(t)) {
            return null;
        }
        return t;
    }

    private static boolean isSubmissionPlaceholder(String text) {
        String n = text.trim();
        return n.equalsIgnoreCase("См. вложенный файл")
                || n.equalsIgnoreCase("См. приложенный файл")
                || n.equalsIgnoreCase("See attached file");
    }

    private static boolean isArchive(String lower) {
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".jar");
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private static boolean isSingleCodeFile(String lower) {
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".jsx") || lower.endsWith(".c") || lower.endsWith(".cpp")
                || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".cs")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".scala")
                || lower.endsWith(".rb") || lower.endsWith(".php") || lower.endsWith(".sql")
                || lower.endsWith(".sh") || lower.endsWith(".swift") || lower.endsWith(".m");
    }
}

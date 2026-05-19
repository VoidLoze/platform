package diplom.platform.aicheck.domain;

/**
 * Унифицированный запрос на AI-проверку: модели не зависят от способа отправки/файлов,
 * подготовкой данных занимается оркестратор.
 */
public record AiCheckRequest(
        AiCheckSubject subject,
        AiCheckSourceType sourceType,
        String studentText,
        String preparedContent,
        String imageBase64,
        String imageMimeType,
        String customInstructions,
        /**
         * Если задано — дополняется user-промпт перед JSON-контрактом (например повторный запрос с недостающими тестами).
         */
        String regenerationHintForUserPrompt
) {
    public AiCheckRequest(
            AiCheckSubject subject,
            AiCheckSourceType sourceType,
            String studentText,
            String preparedContent,
            String imageBase64,
            String imageMimeType,
            String customInstructions) {
        this(subject, sourceType, studentText, preparedContent, imageBase64, imageMimeType, customInstructions, null);
    }
}

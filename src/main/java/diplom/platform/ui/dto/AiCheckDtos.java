package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AiCheckDtos {
    private AiCheckDtos() {
    }

    public record AiCheckCreateRequest(
            String title,
            String subject,
            String sourceType,
            String studentText,
            String attachmentFileKey,
            List<String> attachmentFileKeys,
            String gitUrl,
            String customInstructions,
            String preferredProvider,
            UUID labWorkId
    ) {
    }

    public record AiCheckResultDto(
            UUID id,
            String provider,
            double score,
            double maxScore,
            String summary,
            String detailedFeedback,
            List<String> strengths,
            List<String> issues,
            List<AiCheckFindingDto> findings,
            List<AiCheckTestCaseDto> generatedTests,
            List<AiCheckExecutedTestDto> executedTests,
            AiCheckScopedBlockDto projectResult,
            List<AiCheckScopedBlockDto> fileResults,
            List<AiCheckRecommendationDto> recommendations,
            long latencyMs,
            boolean primary,
            OffsetDateTime createdAt
    ) {
    }

    public record AiCheckFindingDto(
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String severity,
            String title,
            String explanation,
            String snippet
    ) {
    }

    public record AiCheckTestCaseDto(
            String testId,
            String name,
            String kind,
            String purpose,
            String target,
            String scope,
            String filePath,
            List<String> steps,
            String expectedResult,
            /** Одна команда shell в корне проекта; пустая — только ручной чеклист, без автозапуска. */
            String command,
            /** Образ Docker, например python:3.12; если пусто — подберём по языку проекта. */
            String dockerImage,
            /**
             * Текст для стандартного ввода процесса (строки через \\n). Для Java с Scanner платформа
             * также может собрать ввод автоматически из {@code steps}, если поле пустое.
             */
            String stdin
    ) {
    }

    public record AiCheckExecutedTestDto(
            String testId,
            String name,
            String scope,
            String filePath,
            String language,
            String command,
            String status,
            int exitCode,
            String output
    ) {
    }

    public record AiCheckScopedBlockDto(
            String scope,
            String filePath,
            String summary,
            String status,
            List<AiCheckFindingDto> findings,
            List<AiCheckTestCaseDto> generatedTests,
            List<AiCheckExecutedTestDto> executedTests
    ) {
    }

    public record AiCheckRecommendationDto(String title, String description, String url, String resourceType) {
    }

    public record AiCheckJobDto(
            UUID id,
            UUID requesterId,
            UUID labWorkId,
            String subject,
            String sourceType,
            String title,
            String selectedProvider,
            String fallbackChain,
            String status,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<AiCheckResultDto> results
    ) {
    }

    public record AiCheckSettingsDto(
            String defaultProvider,
            List<String> fallbackChain,
            java.util.Map<String, String> subjectPolicy
    ) {
    }

    public record AiCheckProgressEventDto(
            long id,
            String phase,
            String detail,
            String scope,
            String filePath,
            String testId,
            OffsetDateTime createdAt
    ) {
    }
}

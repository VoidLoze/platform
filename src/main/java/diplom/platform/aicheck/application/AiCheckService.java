package diplom.platform.aicheck.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.aicheck.domain.*;
import diplom.platform.aicheck.infrastructure.*;
import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.AIReviewEntity;
import diplom.platform.evaluation.infrastructure.AIReviewJpaRepository;
import diplom.platform.evaluation.infrastructure.LabWorkEntity;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.ui.dto.AIReviewDto;
import diplom.platform.ui.dto.AiCheckDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class AiCheckService {

    private static final Logger log = LoggerFactory.getLogger(AiCheckService.class);
    /** Общие текстовые поля (summary, feedback, snippet, …). */
    private static final int MAX_API_TEXT_FIELD = 2800;
    /** Вывод консоли по одному тесту — много файлов × тесты → держим отдельный лимит. */
    private static final int MAX_API_OUTPUT_FIELD = 2000;
    /** Shell-команда baseline (javac/java …) — иначе JSON job на мегабайты и клиент рвёт соединение. */
    private static final int MAX_API_SHELL_COMMAND = 1000;
    private static final int MAX_API_DOCKER_REF = 240;
    private static final int MAX_API_STDIN_SNIPPET = 1500;

    private final AiCheckJobJpaRepository jobs;
    private final AiCheckResultJpaRepository results;
    private final UserJpaRepository users;
    private final LabWorkJpaRepository labWorks;
    private final AIReviewJpaRepository reviews;
    private final AiCheckPolicyService policy;
    private final AiCheckProviderRegistry registry;
    private final AiCheckSourcePreparator sourcePreparator;
    private final AiCheckLabWorkSubmissionResolver labWorkSubmissionResolver;
    private final CodeExecutionService codeExecutionService;
    private final AiCheckJobProgressService jobProgress;
    private final AiCheckJobStateService jobState;
    private final TransactionTemplate transactionTemplate;
    private final Executor aiCheckExecutor;
    private final boolean skipDeepSeekForAiCheck;
    private final ObjectMapper objectMapper;

    /** Не ставим одну и ту же задачу в пул повторно, пока предыдущий запуск не завершён. */
    private final Set<UUID> jobsInWorkerPipeline = ConcurrentHashMap.newKeySet();

    public AiCheckService(
            AiCheckJobJpaRepository jobs,
            AiCheckResultJpaRepository results,
            UserJpaRepository users,
            LabWorkJpaRepository labWorks,
            AIReviewJpaRepository reviews,
            AiCheckPolicyService policy,
            AiCheckProviderRegistry registry,
            AiCheckSourcePreparator sourcePreparator,
            AiCheckLabWorkSubmissionResolver labWorkSubmissionResolver,
            CodeExecutionService codeExecutionService,
            AiCheckJobProgressService jobProgress,
            AiCheckJobStateService jobState,
            TransactionTemplate transactionTemplate,
            @Qualifier("aiCheckExecutor") Executor aiCheckExecutor,
            @Value("${platform.aicheck.deepseek.skip-for-ai-check:true}") boolean skipDeepSeekForAiCheck,
            ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.results = results;
        this.users = users;
        this.labWorks = labWorks;
        this.reviews = reviews;
        this.policy = policy;
        this.registry = registry;
        this.sourcePreparator = sourcePreparator;
        this.labWorkSubmissionResolver = labWorkSubmissionResolver;
        this.codeExecutionService = codeExecutionService;
        this.jobProgress = jobProgress;
        this.jobState = jobState;
        this.transactionTemplate = transactionTemplate;
        this.aiCheckExecutor = aiCheckExecutor;
        this.skipDeepSeekForAiCheck = skipDeepSeekForAiCheck;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiCheckDtos.AiCheckJobDto createJob(PlatformUser actor, AiCheckDtos.AiCheckCreateRequest req) {
        UserEntity requester = users.findById(actor.id()).orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        AiCheckSourceType sourceType = AiCheckSourceType.parse(req.sourceType());
        String rawSubject = req.subject() == null ? "" : req.subject().trim();
        AiCheckSubject subject = AiCheckSubject.parse(req.subject());
        AiProviderId preferred = AiProviderId.parse(req.preferredProvider());
        List<AiProviderId> chain = policy.resolveProviderChain(subject, preferred);

        AiCheckJobEntity job = new AiCheckJobEntity();
        job.setRequester(requester);
        job.setSubject(subject);
        job.setSourceType(sourceType);
        job.setTitle(req.title());
        job.setStudentText(req.studentText());
        job.setCustomInstructions(mergeCustomInstructionsWithSubject(rawSubject, req.customInstructions()));
        List<String> attachmentKeys = new ArrayList<>();
        if (req.attachmentFileKeys() != null) {
            for (String key : req.attachmentFileKeys()) {
                if (key != null && !key.isBlank() && !attachmentKeys.contains(key.trim())) {
                    attachmentKeys.add(key.trim());
                }
            }
        }
        if (attachmentKeys.isEmpty() && req.attachmentFileKey() != null && !req.attachmentFileKey().isBlank()) {
            attachmentKeys.add(req.attachmentFileKey().trim());
        }
        job.setAttachmentFileKey(attachmentKeys.isEmpty() ? null : attachmentKeys.get(0));
        try {
            job.setAttachmentFileKeysJson(objectMapper.writeValueAsString(attachmentKeys));
        } catch (Exception e) {
            job.setAttachmentFileKeysJson("[]");
        }
        job.setGitUrl(req.gitUrl());
        job.setSelectedProvider(chain.isEmpty() ? null : chain.get(0).name());
        job.setFallbackChain(String.join(",", chain.stream().map(Enum::name).toList()));
        job.setStatus(AiCheckJobEntity.Status.QUEUED);

        if (req.labWorkId() != null) {
            LabWorkEntity lab = labWorks.findDetailById(req.labWorkId()).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
            if (actor.role() == UserRole.ROLE_STUDENT) {
                if (!lab.getStudent().getId().equals(actor.id())) {
                    throw new IllegalArgumentException("Можно проверять только свою работу");
                }
            } else if (actor.role() == UserRole.ROLE_TEACHER) {
                var course = lab.getAssignment().getCourse();
                UUID ownerId = course.getOwner() == null ? null : course.getOwner().getId();
                if (ownerId == null || !ownerId.equals(actor.id())) {
                    throw new IllegalArgumentException("Можно проверять только работы своей группы");
                }
            } else if (actor.role() != UserRole.ROLE_ADMIN) {
                throw new IllegalArgumentException("Недостаточно прав для привязки к работе");
            }
            job.setLabWork(lab);
            lab.setStatus(LabWorkStatus.IN_REVIEW);
            labWorks.save(lab);
        }
        return toJobDto(jobs.save(job), List.of());
    }

    /**
     * Полная ИИ-проверка сданной работы: учитывает текст, файл или оба (что есть).
     */
    @Transactional
    public AiCheckDtos.AiCheckJobDto createJobFromLabWork(UUID labWorkId, PlatformUser actor) {
        LabWorkEntity lab = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        assertLabWorkReviewAccess(lab, actor);

        AiCheckLabWorkSubmissionResolver.ResolvedSubmission resolved = labWorkSubmissionResolver.resolve(
                lab.getTextContent(),
                lab.getAttachmentKey()
        );

        String subjectArea = lab.getAssignment().getSubjectArea();
        String subject = subjectArea == null || subjectArea.isBlank() ? "GENERAL" : subjectArea.trim();
        String title = lab.getAssignment().getTopicTitle();
        String custom = buildLabWorkReviewInstructions(lab);

        AiCheckDtos.AiCheckCreateRequest req = new AiCheckDtos.AiCheckCreateRequest(
                title,
                subject,
                resolved.sourceType().name(),
                resolved.studentText(),
                resolved.attachmentFileKey(),
                resolved.attachmentFileKey() == null ? null : List.of(resolved.attachmentFileKey()),
                null,
                custom,
                null,
                labWorkId
        );
        return createJob(actor, req);
    }

    private void assertLabWorkReviewAccess(LabWorkEntity lab, PlatformUser actor) {
        if (actor.role() == UserRole.ROLE_ADMIN) {
            return;
        }
        if (actor.role() == UserRole.ROLE_TEACHER) {
            var course = lab.getAssignment().getCourse();
            UUID ownerId = course.getOwner() == null ? null : course.getOwner().getId();
            if (ownerId != null && ownerId.equals(actor.id())) {
                return;
            }
            throw new IllegalArgumentException("Можно проверять только работы своей группы");
        }
        if (actor.role() == UserRole.ROLE_STUDENT && lab.getStudent().getId().equals(actor.id())) {
            return;
        }
        throw new IllegalArgumentException("Недостаточно прав для проверки этой работы");
    }

    private static String buildLabWorkReviewInstructions(LabWorkEntity lab) {
        StringBuilder sb = new StringBuilder("Проверь сданную работу студента целиком: ");
        String studentComment = AiCheckLabWorkSubmissionResolver.normalizeStudentText(lab.getTextContent());
        boolean hasText = studentComment != null;
        boolean hasFile = lab.getAttachmentKey() != null && !lab.getAttachmentKey().isBlank();
        if (hasText && hasFile) {
            sb.append("учти комментарий студента и прикреплённый файл с кодом. ");
        } else if (hasFile) {
            sb.append("оцени прикреплённый файл. ");
        } else {
            sb.append("оцени текстовый ответ. ");
        }
        if (hasText) {
            sb.append("\n\nКомментарий студента:\n").append(studentComment);
        }
        String desc = lab.getAssignment().getTopicDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append("\n\nУсловие задания:\n").append(desc.trim());
        }
        sb.append("\n\nМаксимальный балл по заданию: ").append(lab.getAssignment().getMaxScore());
        sb.append("\n\nДля кода: в generated_tests укажи минимум 3 разных теста (разные сценарии, stdin и ожидания); ");
        sb.append("платформа дополнительно выполнит baseline-прогоны (компиляция, запуск, проверка API/байткода).");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public AiCheckDtos.AiCheckJobDto getLatestJobForLabWork(UUID labWorkId, PlatformUser actor) {
        LabWorkEntity lab = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        assertLabWorkReviewAccess(lab, actor);
        return jobs.findByLabWork_Id(labWorkId).stream()
                .max(java.util.Comparator.comparing(AiCheckJobEntity::getCreatedAt))
                .map(j -> toJobDto(j, loadResultDtos(j.getId())))
                .orElse(null);
    }

    private String mergeCustomInstructionsWithSubject(String rawSubject, String customInstructions) {
        String subjectLine = (rawSubject == null || rawSubject.isBlank()) ? null : "Предмет/дисциплина: " + rawSubject.trim();
        String custom = (customInstructions == null || customInstructions.isBlank()) ? null : customInstructions.trim();
        if (subjectLine == null) {
            return custom;
        }
        if (custom == null) {
            return subjectLine;
        }
        return subjectLine + "\n" + custom;
    }

    @Transactional(readOnly = true)
    public List<AiCheckDtos.AiCheckJobDto> listMyJobs(PlatformUser actor) {
        return jobs.findByRequester_IdOrderByCreatedAtDesc(actor.id()).stream()
                .map(j -> toJobDto(j, loadResultDtos(j.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AiCheckDtos.AiCheckJobDto getJob(UUID jobId, PlatformUser actor) {
        AiCheckJobEntity job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found"));
        assertJobAccess(job, actor);
        return toJobDto(job, loadResultDtos(job.getId()));
    }

    @Transactional(readOnly = true)
    public void ensureJobAccessible(UUID jobId, PlatformUser actor) {
        AiCheckJobEntity job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found"));
        assertJobAccess(job, actor);
    }

    @Transactional(readOnly = true)
    public List<AiCheckDtos.AiCheckProgressEventDto> listProgressEvents(UUID jobId) {
        return jobProgress.listEvents(jobId);
    }

    private void assertJobAccess(AiCheckJobEntity job, PlatformUser actor) {
        boolean mine = job.getRequester() != null && job.getRequester().getId().equals(actor.id());
        if (!mine && actor.role() != UserRole.ROLE_ADMIN && actor.role() != UserRole.ROLE_TEACHER) {
            throw new IllegalArgumentException("Недостаточно прав для просмотра этой проверки");
        }
    }

    /**
     * Планировщик только ставит задачи в пул: долгий {@code runOne} не должен блокировать единственный поток
     * {@code @Scheduled} (рядом работает outbox в Redis — при зависании Redis вся очередь «молчала» бы вечно).
     * Статус {@code RUNNING} коммитит {@link AiCheckJobStateService#markRunning(UUID)} до тяжёлой работы.
     */
    @Scheduled(fixedDelayString = "${platform.aicheck.worker.delay-ms:3000}")
    public void processQueue() {
        List<AiCheckJobEntity> queued = jobs.findQueued();
        if (!queued.isEmpty()) {
            log.info("ai-check: в очереди {} задач(и)", queued.size());
        }
        for (AiCheckJobEntity snapshot : queued) {
            UUID id = snapshot.getId();
            if (!jobsInWorkerPipeline.add(id)) {
                continue;
            }
            aiCheckExecutor.execute(() -> {
                try {
                    if (!jobState.markRunning(id)) {
                        return;
                    }
                    log.info("ai-check: старт проверки {}", id);
                    transactionTemplate.executeWithoutResult(status -> {
                        AiCheckJobEntity job = jobs.findByIdWithLabWork(id).orElse(null);
                        if (job == null) {
                            return;
                        }
                        try {
                            runOne(job);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (Exception e) {
                    Throwable root = e;
                    if (e instanceof RuntimeException && e.getCause() instanceof Exception) {
                        root = e.getCause();
                    }
                    String msg = root.getMessage() == null ? "Ошибка выполнения" : root.getMessage();
                    log.error("Ошибка AI-проверки {}: {}", id, msg, e);
                    try {
                        jobProgress.emit(id, "JOB_FAILED", msg);
                    } catch (Exception ignored) {
                        // best effort
                    }
                    jobState.markFailed(id, msg);
                } finally {
                    jobsInWorkerPipeline.remove(id);
                }
            });
        }
    }

    private void runOne(AiCheckJobEntity job) throws Exception {
        UUID jid = job.getId();
        try {
            jobProgress.emit(jid, "JOB_STARTED", null);
        } catch (Exception ignored) {
            // best effort — не блокируем проверку
        }

        try {
            jobProgress.emit(jid, "PREPARE_SOURCE", null);
        } catch (Exception ignored) {
        }

        AiCheckSourcePreparator.Prepared prepared = sourcePreparator.prepare(
                job.getSourceType(),
                job.getStudentText(),
                parseAttachmentKeys(job),
                job.getGitUrl());

        try {
            jobProgress.emit(jid, "SOURCE_READY", prepared.preparedContent() == null || prepared.preparedContent().isBlank() ? "multipart/binary" : "text-ready");
        } catch (Exception ignored) {
        }

        List<AiProviderId> chain = parseChain(job.getFallbackChain());
        AiCheckOutcome primary = null;
        StringBuilder errors = new StringBuilder();

        for (AiProviderId id : chain) {
            if (skipDeepSeekForAiCheck && id == AiProviderId.DEEPSEEK) {
                try {
                    jobProgress.emit(jid, "AI_PROVIDER_SKIPPED", "DEEPSEEK (skip-for-ai-check)");
                } catch (Exception ignored) {
                }
                continue;
            }
            if (!registry.isAvailable(id, job.getSourceType())) {
                continue;
            }
            AiCheckProvider provider = registry.find(id).orElse(null);
            if (provider == null) {
                continue;
            }
            try {
                jobProgress.emit(jid, "AI_PROVIDER_TRY", id.name());
            } catch (Exception ignored) {
            }

            AiCheckRequest req = new AiCheckRequest(
                    job.getSubject(),
                    job.getSourceType(),
                    job.getStudentText(),
                    prepared.preparedContent(),
                    prepared.imageBase64(),
                    prepared.imageMimeType(),
                    job.getCustomInstructions());
            AiCheckOutcome out = provider.check(req);
            if (AiCheckPrompts.requiresMandatoryExecutableGeneratedTests(job.getSubject(), job.getSourceType())
                    && generatedExecutableTestsNonCompliant(out.rawModelResponse())) {
                try {
                    jobProgress.emit(jid, "AI_REGENERATE_TESTS", "mandatory_generated_tests_missing");
                } catch (Exception ignored) {
                }
                AiCheckRequest retryReq = new AiCheckRequest(
                        job.getSubject(),
                        job.getSourceType(),
                        job.getStudentText(),
                        prepared.preparedContent(),
                        prepared.imageBase64(),
                        prepared.imageMimeType(),
                        job.getCustomInstructions(),
                        AiCheckPrompts.regenerationHintRetryFullJsonMandatoryTests());
                out = provider.check(retryReq);
            }

            try {
                jobProgress.emit(jid, "AI_PROVIDER_DONE", id.name());
            } catch (Exception ignored) {
            }

            if (codeExecutionService.supports(job.getSourceType())) {
                ParsedRichResult preRun = parseRichResult(out.rawModelResponse());
                List<CodeExecutionService.AiSuggestedTestCommand> suggested = preRun.generatedTests().stream()
                        .map(t -> new CodeExecutionService.AiSuggestedTestCommand(
                                t.name(),
                                t.command() == null ? "" : t.command(),
                                t.dockerImage() == null ? "" : t.dockerImage(),
                                t.stdin() == null ? "" : t.stdin(),
                                t.steps() == null ? List.of() : t.steps()))
                        .toList();
                CodeExecutionService.CodeExecProgressSink testSink =
                        (phase, detail) -> {
                            try {
                                jobProgress.emit(jid, phase, detail);
                            } catch (Exception ignoredSink) {
                            }
                        };

                boolean archiveOrGit = job.getSourceType() == AiCheckSourceType.CODE_ARCHIVE
                        || job.getSourceType() == AiCheckSourceType.CODE_GIT;
                CodeExecutionService.WorkspaceProfile workspaceProfile = archiveOrGit
                        ? codeExecutionService.peekWorkspaceProfile(
                                job.getSourceType(),
                                job.getStudentText(),
                                parseAttachmentKeys(job),
                                job.getGitUrl())
                        : new CodeExecutionService.WorkspaceProfile(
                                CodeExecutionService.WorkspaceKind.PROJECT, false);

                boolean skipProjectRuntimeTests = archiveOrGit
                        && (workspaceProfile.kind() == CodeExecutionService.WorkspaceKind.MINI_TASKS
                        || workspaceProfile.kind() == CodeExecutionService.WorkspaceKind.HYBRID);
                boolean skipPerFileBaseline = archiveOrGit
                        && workspaceProfile.kind() == CodeExecutionService.WorkspaceKind.PROJECT
                        && workspaceProfile.springBootMavenOrGradleProject();

                CodeExecutionService.ExecutionReport testReport;
                if (skipProjectRuntimeTests) {
                    try {
                        jobProgress.emit(jid, "AI_PROJECT_RUNTIME_SKIPPED",
                                "workspace=" + workspaceProfile.kind());
                    } catch (Exception ignored) {
                    }
                    testReport = new CodeExecutionService.ExecutionReport(
                            "Прогон команд по проекту пропущен: набор отдельных runnable-заданий (MINI_TASKS/HYBRID); ниже — baseline по файлам.",
                            List.of(new CodeExecutionService.ExecResult(
                                    "project_runtime_skipped",
                                    "java",
                                    "",
                                    "SKIPPED",
                                    0,
                                    "Для такой структуры архива или репозитория не выполняется общая shell-команда из generated_tests по корню; "
                                            + "остаётся автопрогон по каждому runnable-файлу.")));
                } else if (!suggested.isEmpty()) {
                    try {
                        jobProgress.emit(jid, "AI_SUGGESTED_TESTS_START", "tests=" + suggested.size());
                    } catch (Exception ignored) {
                    }
                    testReport = codeExecutionService.runAiSuggestedTestCommands(
                            job.getSourceType(),
                            job.getStudentText(),
                            parseAttachmentKeys(job),
                            job.getGitUrl(),
                            suggested,
                            testSink);
                    try {
                        jobProgress.emit(jid, "AI_SUGGESTED_TESTS_DONE", testReport.summary());
                    } catch (Exception ignored) {
                    }
                } else {
                    try {
                        jobProgress.emit(jid, "AI_BASELINE_RUNTIME_START", "reason=empty_generated_tests");
                    } catch (Exception ignored) {
                    }
                    testReport = codeExecutionService.execute(
                            job.getSourceType(),
                            job.getStudentText(),
                            parseAttachmentKeys(job),
                            job.getGitUrl(),
                            testSink);
                    try {
                        jobProgress.emit(jid, "AI_BASELINE_RUNTIME_DONE", testReport.summary());
                    } catch (Exception ignored) {
                    }
                }
                out = mergeOutcomeWithSuggestedExecution(out, testReport);

                boolean runPerFileBaseline = archiveOrGit
                        || job.getSourceType() == AiCheckSourceType.TEXT;
                if (runPerFileBaseline) {
                    CodeExecutionService.ScopedExecutionReport scoped;
                    if (skipPerFileBaseline) {
                        try {
                            jobProgress.emit(jid, "AI_FILE_BASELINE_SKIPPED", "spring_boot_project");
                        } catch (Exception ignored) {
                        }
                        scoped = new CodeExecutionService.ScopedExecutionReport(
                                workspaceProfile.kind(),
                                List.of(),
                                "Автопрогон по отдельным runnable-файлам пропущен: обнаружен Spring Boot / Spring Gradle проект "
                                        + "(используются только рекомендованные тесты по корню репозитория).");
                    } else {
                        scoped = codeExecutionService.runBaselinePerRunnableFile(
                                job.getSourceType(),
                                job.getStudentText(),
                                parseAttachmentKeys(job),
                                job.getGitUrl(),
                                (phase, detail) -> {
                                    try {
                                        if ("FILE_RUNTIME_START".equals(phase) || "FILE_RUNTIME_DONE".equals(phase)) {
                                            String fp = null;
                                            if (detail != null && !detail.isBlank()) {
                                                fp = detail.split(";", 2)[0].trim();
                                            }
                                            jobProgress.emit(jid, phase, detail, "FILE", fp, null);
                                        } else {
                                            jobProgress.emit(jid, phase, detail);
                                        }
                                    } catch (Exception ignoredSink) {
                                    }
                                });
                    }
                    out = mergeOutcomeWithScopedFileExecution(out, scoped);
                }
            }

            saveResult(job, out, primary == null);
            if (out.score() > 0) {
                primary = out;
                break;
            }
            errors.append(id.name()).append(": ").append(out.detailedFeedback()).append("; ");
        }

        if (primary == null) {
            throw new IllegalStateException("Все провайдеры недоступны: " + errors);
        }

        if (job.getLabWork() != null) {
            applyToLabWork(job.getLabWork().getId(), primary);
        }
        job.setStatus(AiCheckJobEntity.Status.DONE);
        job.setFinishedAt(OffsetDateTime.now());
        job.setErrorMessage(null);
        jobs.saveAndFlush(job);

        try {
            jobProgress.emit(jid, "JOB_DONE", null);
        } catch (Exception ignored) {
        }
    }

    private void applyToLabWork(UUID labWorkId, AiCheckOutcome out) {
        LabWorkEntity lab = labWorks.findDetailById(labWorkId)
                .orElseThrow(() -> new IllegalStateException("Lab work not found: " + labWorkId));
        double maxScore = lab.getAssignment().getMaxScore();

        AIReviewEntity review = reviews.findByLabWorkId(labWorkId).orElse(new AIReviewEntity());
        review.setLabWork(lab);
        review.setScoreValue(out.score());
        review.setMaxScore(maxScore);
        review.setSummary(out.summary());
        review.setDetailedFeedback(out.detailedFeedback());
        String recommendations = out.recommendations().stream()
                .map(r -> r.title() + " — " + r.description() + (r.url() == null || r.url().isBlank() ? "" : " (" + r.url() + ")"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        review.setRecommendations(recommendations);
        reviews.save(review);

        lab.setStatus(LabWorkStatus.REVIEW_READY);
        lab.setFinalGradeValue(out.score());
        lab.setFinalGradeLetter(out.score() >= maxScore * 0.6 ? "PASS" : "FAIL");
        labWorks.save(lab);
    }

    private void saveResult(AiCheckJobEntity job, AiCheckOutcome out, boolean primary) throws Exception {
        AiCheckResultEntity r = new AiCheckResultEntity();
        r.setJob(job);
        r.setProvider(out.provider().name());
        r.setScore(out.score());
        r.setMaxScore(out.maxScore());
        r.setSummary(out.summary());
        r.setDetailedFeedback(out.detailedFeedback());
        r.setStrengthsJson(objectMapper.writeValueAsString(out.strengths()));
        r.setIssuesJson(objectMapper.writeValueAsString(out.issues()));
        r.setRecommendationsJson(objectMapper.writeValueAsString(out.recommendations()));
        r.setRawResponse(out.rawModelResponse());
        r.setLatencyMs(out.latencyMs());
        r.setPrimary(primary);
        results.save(r);
    }

    private List<AiProviderId> parseChain(String csv) {
        List<AiProviderId> out = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                AiProviderId id = AiProviderId.parse(s);
                if (id != null && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(List.of(AiProviderId.GIGACHAT, AiProviderId.DEEPSEEK, AiProviderId.QWEN));
        }
        return out;
    }

    private List<AiCheckDtos.AiCheckResultDto> loadResultDtos(UUID jobId) {
        return results.findByJob_IdOrderByCreatedAtAsc(jobId).stream().map(r -> {
            List<String> strengths = parseStringArray(r.getStrengthsJson());
            List<String> issues = parseStringArray(r.getIssuesJson());
            List<AiCheckDtos.AiCheckRecommendationDto> recs = parseRecommendations(r.getRecommendationsJson());
            ParsedRichResult rich = parseRichResult(r.getRawResponse());
            return new AiCheckDtos.AiCheckResultDto(
                    r.getId(), r.getProvider(), r.getScore(), r.getMaxScore(),
                    r.getSummary(), r.getDetailedFeedback(), strengths, issues,
                    rich.findings(), rich.generatedTests(), rich.executedTests(),
                    rich.projectResult(), rich.fileResults(), recs,
                    r.getLatencyMs(), r.isPrimary(), r.getCreatedAt());
        }).toList();
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<AiCheckDtos.AiCheckRecommendationDto> parseRecommendations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<java.util.Map<String, Object>> arr = objectMapper.readValue(json, new TypeReference<>() {});
            return arr.stream().map(m -> new AiCheckDtos.AiCheckRecommendationDto(
                    capText(String.valueOf(m.getOrDefault("title", ""))),
                    capText(String.valueOf(m.getOrDefault("description", ""))),
                    String.valueOf(m.getOrDefault("url", "")),
                    String.valueOf(m.getOrDefault("resourceType", m.getOrDefault("resource_type", "article")))
            )).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private AiCheckOutcome mergeOutcomeWithSuggestedExecution(AiCheckOutcome out, CodeExecutionService.ExecutionReport report) {
        if (report == null || report.results() == null || report.results().isEmpty()) {
            return out;
        }
        String enrichedRaw = injectExecutedTestsIntoRaw(out.rawModelResponse(), report);
        return new AiCheckOutcome(
                out.provider(),
                out.score(),
                out.maxScore(),
                out.summary(),
                out.detailedFeedback(),
                out.strengths(),
                out.issues(),
                out.recommendations(),
                enrichedRaw,
                out.latencyMs()
        );
    }

    private AiCheckOutcome mergeOutcomeWithScopedFileExecution(AiCheckOutcome out, CodeExecutionService.ScopedExecutionReport scoped) {
        if (scoped == null) {
            return out;
        }
        List<CodeExecutionService.RunnableUnitResult> fr = scoped.fileResults();
        if (fr == null || fr.isEmpty()) {
            if (scoped.fileBaselineSkipNote() != null && !scoped.fileBaselineSkipNote().isBlank()) {
                String enriched = injectScopedBaselineMeta(
                        out.rawModelResponse(), scoped.workspaceKind(), scoped.fileBaselineSkipNote());
                return new AiCheckOutcome(
                        out.provider(),
                        out.score(),
                        out.maxScore(),
                        out.summary(),
                        out.detailedFeedback(),
                        out.strengths(),
                        out.issues(),
                        out.recommendations(),
                        enriched,
                        out.latencyMs());
            }
            return out;
        }
        String enrichedRaw = injectScopedFileResultsIntoRaw(out.rawModelResponse(), scoped);
        return new AiCheckOutcome(
                out.provider(),
                out.score(),
                out.maxScore(),
                out.summary(),
                out.detailedFeedback(),
                out.strengths(),
                out.issues(),
                out.recommendations(),
                enrichedRaw,
                out.latencyMs()
        );
    }

    private String injectScopedBaselineMeta(
            String raw,
            CodeExecutionService.WorkspaceKind kind,
            String note
    ) {
        try {
            JsonNode rootNode = objectMapper.readTree(extractJsonObject(raw == null ? "{}" : raw));
            var root = rootNode.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) rootNode : objectMapper.createObjectNode();
            root.put("workspace_kind", kind.name());
            if (note != null && !note.isBlank()) {
                root.put("file_baseline_skip_note", capText(note));
            }
            root.putArray("file_results");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return raw;
        }
    }

    private String injectExecutedTestsIntoRaw(String raw, CodeExecutionService.ExecutionReport report) {
        try {
            JsonNode rootNode = objectMapper.readTree(extractJsonObject(raw == null ? "{}" : raw));
            var root = rootNode.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) rootNode : objectMapper.createObjectNode();
            root.put("execution_summary", capText(report.summary()));
            var arr = root.putArray("executed_tests");
            for (CodeExecutionService.ExecResult r : report.results()) {
                var item = arr.addObject();
                item.put("name", r.name());
                item.put("language", r.language());
                item.put("command", capShell(r.command()));
                item.put("status", r.status());
                item.put("exit_code", r.exitCode());
                item.put("output", capOutput(r.output()));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return raw;
        }
    }

    private String injectScopedFileResultsIntoRaw(String raw, CodeExecutionService.ScopedExecutionReport scoped) {
        try {
            JsonNode rootNode = objectMapper.readTree(extractJsonObject(raw == null ? "{}" : raw));
            var root = rootNode.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) rootNode : objectMapper.createObjectNode();
            root.put("workspace_kind", scoped.workspaceKind().name());
            var files = root.putArray("file_results");
            for (CodeExecutionService.RunnableUnitResult ur : scoped.fileResults()) {
                var unit = ur.unit();
                var block = files.addObject();
                block.put("scope", "FILE");
                block.put("file_path", unit.filePath());
                block.put("status", ur.execution().results().stream().allMatch(r -> "PASSED".equals(r.status())) ? "PASSED" : "FAILED");
                block.put("summary", capText(ur.execution().summary()));
                var gen = block.putArray("generated_tests");
                int suggestedCount = Math.max(3, ur.execution().results().size());
                List<String> testIds = new ArrayList<>(suggestedCount);
                for (int i = 1; i <= suggestedCount; i++) {
                    String testId = "file-" + unit.filePath().replace('/', '_') + "-" + i;
                    testIds.add(testId);
                    var genItem = gen.addObject();
                    genItem.put("test_id", testId);
                    genItem.put("name", "Baseline runtime #" + i + " for " + unit.filePath());
                    genItem.put("kind", "unit");
                    genItem.put("purpose", "Базовая проверка runnable-файла");
                    genItem.put("target", unit.filePath());
                    genItem.put("scope", "FILE");
                    genItem.put("file_path", unit.filePath());
                    genItem.put("expected_result", "Код компилируется/запускается без ошибок");
                    genItem.put("command", capShell(unit.command()));
                    genItem.put("docker_image", capDockerRef(unit.dockerImage()));
                    genItem.put("stdin", "");
                }
                block.putArray("findings");
                var ex = block.putArray("executed_tests");
                int ri = 0;
                for (CodeExecutionService.ExecResult r : ur.execution().results()) {
                    var item = ex.addObject();
                    item.put("test_id", ri < testIds.size() ? testIds.get(ri) : "");
                    item.put("name", r.name());
                    item.put("scope", "FILE");
                    item.put("file_path", unit.filePath());
                    item.put("language", r.language());
                    item.put("command", capShell(r.command()));
                    item.put("status", r.status());
                    item.put("exit_code", r.exitCode());
                    item.put("output", capOutput(r.output()));
                    ri++;
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return raw;
        }
    }

    /** Минимум 3 теста; у каждого непустые command и docker_image (требование платформы к прогону). */
    private boolean generatedExecutableTestsNonCompliant(String raw) {
        ParsedRichResult pr = parseRichResult(raw);
        List<AiCheckDtos.AiCheckTestCaseDto> tests = pr.generatedTests();
        if (tests.size() < 3) {
            return true;
        }
        for (AiCheckDtos.AiCheckTestCaseDto t : tests) {
            if (t.command() == null || t.command().isBlank()) {
                return true;
            }
            if (t.dockerImage() == null || t.dockerImage().isBlank()) {
                return true;
            }
            String cmd = t.command() == null ? "" : t.command().trim().toLowerCase();
            boolean javaCommand = cmd.startsWith("java ") || cmd.contains(" java ");
            if (javaCommand) {
                boolean hasAnyInputHint =
                        (t.stdin() != null && !t.stdin().isBlank())
                                || (t.steps() != null && !t.steps().isEmpty());
                if (!hasAnyInputHint) {
                    return true;
                }
            }
        }
        return false;
    }

    private ParsedRichResult parseRichResult(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new ParsedRichResult(List.of(), List.of(), List.of(), null, List.of());
        }
        try {
            String json = extractJsonObject(rawResponse);
            JsonNode root = objectMapper.readTree(json);
            List<AiCheckDtos.AiCheckFindingDto> findings = new ArrayList<>();
            JsonNode findingsNode = root.path("findings");
            if (findingsNode.isArray()) {
                for (JsonNode f : findingsNode) {
                    findings.add(new AiCheckDtos.AiCheckFindingDto(
                            f.path("file_path").asText(""),
                            f.path("line_start").isNumber() ? f.path("line_start").asInt() : null,
                            f.path("line_end").isNumber() ? f.path("line_end").asInt() : null,
                            f.path("severity").asText("info"),
                            capText(f.path("title").asText("")),
                            capText(f.path("explanation").asText("")),
                            capText(f.path("snippet").asText(""))
                    ));
                }
            }
            List<AiCheckDtos.AiCheckTestCaseDto> tests = new ArrayList<>();
            JsonNode testsNode = root.path("generated_tests");
            if (testsNode.isArray()) {
                for (JsonNode t : testsNode) {
                    List<String> steps = new ArrayList<>();
                    JsonNode stepsNode = t.path("steps");
                    if (stepsNode.isArray()) {
                        for (JsonNode s : stepsNode) {
                            String step = trunc(s.asText("").trim(), 512);
                            if (!step.isEmpty()) {
                                steps.add(step);
                            }
                        }
                    }
                    String cmd = t.path("command").asText("").trim();
                    if (cmd.isEmpty()) {
                        cmd = t.path("run_command").asText("").trim();
                    }
                    String dockerImg = t.path("docker_image").asText("").trim();
                    if (dockerImg.isEmpty()) {
                        dockerImg = t.path("dockerImage").asText("").trim();
                    }
                    String stdin = t.path("stdin").asText("").trim();
                    if (stdin.isEmpty()) {
                        stdin = t.path("stdin_text").asText("").trim();
                    }
                    if (stdin.isEmpty()) {
                        stdin = t.path("standard_input").asText("").trim();
                    }
                    tests.add(new AiCheckDtos.AiCheckTestCaseDto(
                            t.path("test_id").asText(""),
                            capText(t.path("name").asText("")),
                            t.path("kind").asText("manual"),
                            capText(t.path("purpose").asText("")),
                            capText(t.path("target").asText("")),
                            t.path("scope").asText("PROJECT"),
                            t.path("file_path").asText(""),
                            steps,
                            capText(t.path("expected_result").asText("")),
                            capShell(cmd),
                            capDockerRef(dockerImg),
                            trunc(stdin, MAX_API_STDIN_SNIPPET)
                    ));
                }
            }
            List<AiCheckDtos.AiCheckExecutedTestDto> executed = new ArrayList<>();
            JsonNode executedNode = root.path("executed_tests");
            if (executedNode.isArray()) {
                for (JsonNode ex : executedNode) {
                    executed.add(new AiCheckDtos.AiCheckExecutedTestDto(
                            ex.path("test_id").asText(""),
                            capText(ex.path("name").asText("")),
                            ex.path("scope").asText("PROJECT"),
                            ex.path("file_path").asText(""),
                            ex.path("language").asText(""),
                            capShell(ex.path("command").asText("")),
                            ex.path("status").asText(""),
                            ex.path("exit_code").isNumber() ? ex.path("exit_code").asInt() : -1,
                            capOutput(ex.path("output").asText(""))
                    ));
                }
            }
            AiCheckDtos.AiCheckScopedBlockDto project = new AiCheckDtos.AiCheckScopedBlockDto(
                    "PROJECT",
                    "",
                    capText(root.path("execution_summary").asText("")),
                    executed.stream().allMatch(e -> "PASSED".equals(e.status())) ? "PASSED" : (executed.isEmpty() ? "UNKNOWN" : "FAILED"),
                    findings,
                    tests,
                    executed
            );
            List<AiCheckDtos.AiCheckScopedBlockDto> fileResults =
                    mergeGlobalFindingsIntoFileBlocks(parseFileResults(root.path("file_results")), findings);
            return new ParsedRichResult(findings, tests, executed, project, fileResults);
        } catch (Exception e) {
            return new ParsedRichResult(List.of(), List.of(), List.of(), null, List.of());
        }
    }

    private List<AiCheckDtos.AiCheckScopedBlockDto> parseFileResults(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AiCheckDtos.AiCheckScopedBlockDto> out = new ArrayList<>();
        for (JsonNode b : node) {
            String fp = b.path("file_path").asText("");
            String summary = capText(b.path("summary").asText(""));
            String status = b.path("status").asText("UNKNOWN");
            List<AiCheckDtos.AiCheckFindingDto> findings = new ArrayList<>();
            JsonNode fn = b.path("findings");
            if (fn.isArray()) {
                for (JsonNode f : fn) {
                    findings.add(new AiCheckDtos.AiCheckFindingDto(
                            f.path("file_path").asText(fp),
                            f.path("line_start").isNumber() ? f.path("line_start").asInt() : null,
                            f.path("line_end").isNumber() ? f.path("line_end").asInt() : null,
                            f.path("severity").asText("info"),
                            capText(f.path("title").asText("")),
                            capText(f.path("explanation").asText("")),
                            capText(f.path("snippet").asText(""))
                    ));
                }
            }
            List<AiCheckDtos.AiCheckTestCaseDto> tests = new ArrayList<>();
            JsonNode tn = b.path("generated_tests");
            if (tn.isArray()) {
                for (JsonNode t : tn) {
                    tests.add(new AiCheckDtos.AiCheckTestCaseDto(
                            t.path("test_id").asText(""),
                            capText(t.path("name").asText("")),
                            t.path("kind").asText("manual"),
                            capText(t.path("purpose").asText("")),
                            capText(t.path("target").asText(fp)),
                            t.path("scope").asText("FILE"),
                            t.path("file_path").asText(fp),
                            List.of(),
                            capText(t.path("expected_result").asText("")),
                            capShell(t.path("command").asText("")),
                            capDockerRef(t.path("docker_image").asText("")),
                            trunc(t.path("stdin").asText(""), MAX_API_STDIN_SNIPPET)
                    ));
                }
            }
            List<AiCheckDtos.AiCheckExecutedTestDto> executed = new ArrayList<>();
            JsonNode en = b.path("executed_tests");
            if (en.isArray()) {
                for (JsonNode ex : en) {
                    executed.add(new AiCheckDtos.AiCheckExecutedTestDto(
                            ex.path("test_id").asText(""),
                            capText(ex.path("name").asText("")),
                            ex.path("scope").asText("FILE"),
                            ex.path("file_path").asText(fp),
                            ex.path("language").asText(""),
                            capShell(ex.path("command").asText("")),
                            ex.path("status").asText(""),
                            ex.path("exit_code").isNumber() ? ex.path("exit_code").asInt() : -1,
                            capOutput(ex.path("output").asText(""))
                    ));
                }
            }
            out.add(new AiCheckDtos.AiCheckScopedBlockDto("FILE", fp, summary, status, findings, tests, executed));
        }
        return out;
    }

    private List<AiCheckDtos.AiCheckScopedBlockDto> mergeGlobalFindingsIntoFileBlocks(
            List<AiCheckDtos.AiCheckScopedBlockDto> fileResults,
            List<AiCheckDtos.AiCheckFindingDto> globalFindings
    ) {
        if (fileResults == null || fileResults.isEmpty()) {
            return List.of();
        }
        List<AiCheckDtos.AiCheckFindingDto> globals = globalFindings == null ? List.of() : globalFindings;
        List<AiCheckDtos.AiCheckScopedBlockDto> merged = new ArrayList<>(fileResults.size());
        for (AiCheckDtos.AiCheckScopedBlockDto block : fileResults) {
            String fp = block.filePath() == null ? "" : block.filePath();
            List<AiCheckDtos.AiCheckFindingDto> combined = new ArrayList<>();
            if (block.findings() != null) {
                combined.addAll(block.findings());
            }
            for (AiCheckDtos.AiCheckFindingDto gf : globals) {
                String gfp = gf.filePath() == null ? "" : gf.filePath();
                if (gfp.isBlank() || fp.isBlank()) {
                    continue;
                }
                if (gfp.equals(fp)) {
                    combined.add(gf);
                }
            }
            merged.add(new AiCheckDtos.AiCheckScopedBlockDto(
                    block.scope(),
                    block.filePath(),
                    block.summary(),
                    block.status(),
                    combined,
                    block.generatedTests(),
                    block.executedTests()
            ));
        }
        return merged;
    }

    private static String trunc(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "\n...truncated...";
    }

    private static String capText(String s) {
        return trunc(s, MAX_API_TEXT_FIELD);
    }

    private static String capOutput(String s) {
        return trunc(s, MAX_API_OUTPUT_FIELD);
    }

    private static String capShell(String s) {
        return trunc(s, MAX_API_SHELL_COMMAND);
    }

    private static String capDockerRef(String s) {
        return trunc(s, MAX_API_DOCKER_REF);
    }

    private String extractJsonObject(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private static AiCheckDtos.AiCheckJobDto toJobDto(AiCheckJobEntity j, List<AiCheckDtos.AiCheckResultDto> results) {
        return new AiCheckDtos.AiCheckJobDto(
                j.getId(),
                j.getRequester() == null ? null : j.getRequester().getId(),
                j.getLabWork() == null ? null : j.getLabWork().getId(),
                j.getSubject() == null ? null : j.getSubject().name(),
                j.getSourceType() == null ? null : j.getSourceType().name(),
                j.getTitle(),
                j.getSelectedProvider(),
                j.getFallbackChain(),
                j.getStatus().name(),
                j.getErrorMessage(),
                j.getCreatedAt(),
                j.getStartedAt(),
                j.getFinishedAt(),
                results
        );
    }

    private List<String> parseAttachmentKeys(AiCheckJobEntity job) {
        if (job.getAttachmentFileKeysJson() != null && !job.getAttachmentFileKeysJson().isBlank()) {
            try {
                return objectMapper.readValue(job.getAttachmentFileKeysJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                // fallback to single key
            }
        }
        if (job.getAttachmentFileKey() == null || job.getAttachmentFileKey().isBlank()) {
            return List.of();
        }
        return List.of(job.getAttachmentFileKey());
    }

    private record ParsedRichResult(
            List<AiCheckDtos.AiCheckFindingDto> findings,
            List<AiCheckDtos.AiCheckTestCaseDto> generatedTests,
            List<AiCheckDtos.AiCheckExecutedTestDto> executedTests,
            AiCheckDtos.AiCheckScopedBlockDto projectResult,
            List<AiCheckDtos.AiCheckScopedBlockDto> fileResults
    ) {
    }
}

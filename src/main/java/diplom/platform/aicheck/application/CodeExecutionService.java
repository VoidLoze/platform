package diplom.platform.aicheck.application;

import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.infrastructure.storage.FileStorageService;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(180);
    /** Защита от мусора/инъекций в поле command у ИИ-подсказки. */
    private static final int MAX_MODEL_SHELL_COMMAND_CHARS = 20_000;

    private static final Pattern STEP_TRAILING_MANUAL_NOTE =
            Pattern.compile("(?iu)\\s*\\(\\s*(?:ручная\\s*проверка|manual).*");
    /** Согласовано с лимитом runner на stdin multipart. */
    private static final int MAX_STDIN_CHARS = 65_536;
    private static final int MAX_STEP_STRING_CHARS = 4_096;
    private static final int MAX_STEPS_COUNT = 256;
    /** Доп. строки stdin при while(true)+Scanner без hasNextLine(), чтобы не падать на EOF. */
    private static final int SCANNER_EOF_PAD_MAX_LINES = 420;
    /**
     * Минимум строк «по одному токену на строку»: в исходнике один вызов {@code nextLine()} в цикле
     * даёт один матч regex, а фактически чтений много — иначе {@link java.util.NoSuchElementException}.
     */
    private static final int SCANNER_BASELINE_MIN_SINGLETON_LINES = 720;

    private final FileStorageService files;
    private final AiCheckDockerRunnerClient runner;
    private final String dockerCpus;
    private final String dockerMemory;
    private final long maxRunnerArchiveBytes;

    public CodeExecutionService(
            FileStorageService files,
            AiCheckDockerRunnerClient runner,
            @Value("${platform.aicheck.docker.cpus:2}") String dockerCpus,
            @Value("${platform.aicheck.docker.memory:1g}") String dockerMemory,
            @Value("${platform.aicheck.runner.max-archive-bytes:52428800}") long maxRunnerArchiveBytes
    ) {
        this.files = files;
        this.runner = runner;
        this.dockerCpus = dockerCpus == null || dockerCpus.isBlank() ? "2" : dockerCpus.trim();
        this.dockerMemory = dockerMemory == null || dockerMemory.isBlank() ? "1g" : dockerMemory.trim();
        this.maxRunnerArchiveBytes = maxRunnerArchiveBytes;
    }

    @FunctionalInterface
    public interface CodeExecProgressSink {
        void emit(String phase, String detail);

        CodeExecProgressSink NOOP = (a, b) -> {
        };
    }

    public boolean supports(AiCheckSourceType sourceType) {
        return sourceType == AiCheckSourceType.TEXT
                || sourceType == AiCheckSourceType.CODE_ARCHIVE
                || sourceType == AiCheckSourceType.CODE_GIT;
    }

    public enum WorkspaceKind {
        PROJECT,
        MINI_TASKS,
        HYBRID
    }

    /** Классификация дерева файлов до прогона (архив/git): отдельные задания vs монорепо, признак Spring Boot. */
    public record WorkspaceProfile(WorkspaceKind kind, boolean springBootMavenOrGradleProject) {
    }

    public record RunnableUnit(
            String filePath,
            String language,
            String launchClass,
            String command,
            String dockerImage
    ) {
    }

    public record ScopedExecutionReport(
            WorkspaceKind workspaceKind,
            List<RunnableUnitResult> fileResults,
            /** Непусто, если baseline по файлам намеренно не запускали (например Spring Boot монорепо). */
            String fileBaselineSkipNote
    ) {
        public ScopedExecutionReport(WorkspaceKind workspaceKind, List<RunnableUnitResult> fileResults) {
            this(workspaceKind, fileResults, null);
        }
    }

    public record RunnableUnitResult(
            RunnableUnit unit,
            ExecutionReport execution
    ) {
    }

    /** Три разных шага baseline для одного Java-runnable (команда + stdin). */
    private record BaselineDockerStep(String name, String shellCommand, String stdinText) {
    }

    public WorkspaceKind classifyWorkspace(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl
    ) {
        if (!supports(sourceType)) {
            return WorkspaceKind.PROJECT;
        }
        if (sourceType == AiCheckSourceType.CODE_ARCHIVE || sourceType == AiCheckSourceType.CODE_GIT) {
            return peekWorkspaceProfile(sourceType, studentText, attachmentKeys, gitUrl).kind();
        }
        Path workspace = null;
        try {
            workspace = materializeWorkspace(sourceType, studentText, attachmentKeys, gitUrl);
            return classifyWorkspace(workspace);
        } catch (Exception e) {
            return WorkspaceKind.PROJECT;
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * Один раз материализует workspace (архив/git) и возвращает классификацию без прогона.
     * Нужна для политики: набор лаб vs Spring-проект.
     */
    public WorkspaceProfile peekWorkspaceProfile(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl
    ) {
        if (sourceType != AiCheckSourceType.CODE_ARCHIVE && sourceType != AiCheckSourceType.CODE_GIT) {
            return new WorkspaceProfile(WorkspaceKind.PROJECT, false);
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("peek-workspace-");
            switch (sourceType) {
                case CODE_ARCHIVE -> materializeArchive(workspace, attachmentKeys);
                case CODE_GIT -> materializeGit(workspace, gitUrl);
                default -> {
                    return new WorkspaceProfile(WorkspaceKind.PROJECT, false);
                }
            }
            WorkspaceKind kind = classifyWorkspace(workspace);
            boolean spring = (kind == WorkspaceKind.PROJECT) && isSpringBootProject(workspace);
            return new WorkspaceProfile(kind, spring);
        } catch (Exception e) {
            log.warn("peekWorkspaceProfile: {}", e.getMessage());
            return new WorkspaceProfile(WorkspaceKind.PROJECT, false);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static boolean isSpringBootProject(Path workspace) {
        try {
            Path pom = workspace.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String s = Files.readString(pom, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (s.contains("spring-boot-starter")
                        || s.contains("org.springframework.boot")
                        || s.contains("spring-boot-starter-parent")) {
                    return true;
                }
            }
            for (String gradleName : List.of("build.gradle", "build.gradle.kts")) {
                Path g = workspace.resolve(gradleName);
                if (Files.isRegularFile(g)) {
                    String s = Files.readString(g, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (s.contains("spring-boot") || s.contains("org.springframework.boot")) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    public ScopedExecutionReport runBaselinePerRunnableFile(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl,
            CodeExecProgressSink sink
    ) {
        CodeExecProgressSink progress = sink == null ? CodeExecProgressSink.NOOP : sink;
        if (!supports(sourceType)) {
            return new ScopedExecutionReport(WorkspaceKind.PROJECT, List.of());
        }
        Path workspace = null;
        try {
            workspace = materializeWorkspace(sourceType, studentText, attachmentKeys, gitUrl);
            WorkspaceKind kind = classifyWorkspace(workspace);
            List<RunnableUnit> units = discoverRunnableUnits(workspace);
            if (units.isEmpty()) {
                return new ScopedExecutionReport(kind, List.of());
            }
            List<RunnableUnitResult> out = new ArrayList<>();
            for (RunnableUnit u : units) {
                progress.emit("FILE_RUNTIME_START", u.filePath());
                List<ExecResult> runs = new ArrayList<>();
                try {
                    List<BaselineDockerStep> steps = buildBaselineStepsForUnit(workspace, u);
                    for (BaselineDockerStep step : steps) {
                        ExecResult base = runDocker(
                                workspace, u.dockerImage(), u.language(), step.shellCommand(), step.stdinText(), progress);
                        runs.add(new ExecResult(
                                step.name(),
                                base.language(),
                                base.command(),
                                base.status(),
                                base.exitCode(),
                                base.output()
                        ));
                    }
                } catch (IOException e) {
                    log.warn("baseline steps {}: {}", u.filePath(), e.getMessage());
                    String stdin = baselineStdinForRunnable(workspace, u);
                    for (int i = 1; i <= 3; i++) {
                        ExecResult base = runDocker(workspace, u.dockerImage(), u.language(), u.command(), stdin, progress);
                        runs.add(new ExecResult(
                                "Baseline #" + i + " (fallback) · " + u.filePath(),
                                base.language(),
                                base.command(),
                                base.status(),
                                base.exitCode(),
                                base.output()
                        ));
                    }
                }
                boolean allPassed = runs.stream().allMatch(x -> "PASSED".equals(x.status()));
                out.add(new RunnableUnitResult(u, new ExecutionReport(
                        "Unit runtime: " + u.filePath() + " · " + (allPassed ? "PASSED" : "FAILED"),
                        runs)));
                progress.emit("FILE_RUNTIME_DONE", u.filePath() + "; status=" + (allPassed ? "PASSED" : "FAILED"));
            }
            return new ScopedExecutionReport(kind, out);
        } catch (Exception e) {
            return new ScopedExecutionReport(WorkspaceKind.PROJECT, List.of());
        } finally {
            deleteRecursively(workspace);
        }
    }

    private List<BaselineDockerStep> buildBaselineStepsForUnit(Path workspace, RunnableUnit u) throws IOException {
        if (!"java".equalsIgnoreCase(u.language())) {
            String stdin = baselineStdinForRunnable(workspace, u);
            return List.of(
                    new BaselineDockerStep("Baseline #1 run · " + u.filePath(), u.command(), stdin),
                    new BaselineDockerStep("Baseline #2 run · " + u.filePath(), u.command(), stdin),
                    new BaselineDockerStep("Baseline #3 run · " + u.filePath(), u.command(), stdin));
        }
        String rel = u.filePath();
        if (rel == null || rel.isBlank()) {
            return javaFallbackTriple(u);
        }
        Path p = workspace.resolve(rel).normalize();
        if (!p.startsWith(workspace) || !Files.isRegularFile(p)) {
            return javaFallbackTriple(u);
        }
        String src = Files.readString(p, StandardCharsets.UTF_8);
        String compile = javacCompileAllSourcesToOutDir();
        String fqcn = u.launchClass() == null ? "" : u.launchClass().trim();
        if (fqcn.isBlank()) {
            return javaFallbackTriple(u);
        }
        String runStdin = capUtf16(buildDefaultJavaScannerStdinFromSource(src), MAX_STDIN_CHARS);
        boolean pipeFeed = needsPipedStdinForLoopBodyScanner(src);
        String runCmd = pipeFeed ? compile + " && " + shStaggeredPairsToJava(fqcn) : u.command();
        String runIn = pipeFeed ? "" : runStdin;
        String classFile = JAVA_PLATFORM_CLASS_DIR + "/" + fqcn.replace('.', '/') + ".class";
        return List.of(
                new BaselineDockerStep("Baseline #1 compile+run · " + rel, runCmd, runIn),
                new BaselineDockerStep(
                        "Baseline #2 javap public API · " + rel,
                        compile + " && javap -cp " + JAVA_PLATFORM_CLASS_DIR + " -public " + fqcn + " 2>&1 | head -n 200",
                        ""),
                new BaselineDockerStep(
                        "Baseline #3 bytecode on disk · " + rel,
                        compile + " && test -f " + classFile + " && echo CLASS_OK",
                        ""));
    }

    private static List<BaselineDockerStep> javaFallbackTriple(RunnableUnit u) {
        return List.of(
                new BaselineDockerStep("Baseline #1 · " + u.filePath(), u.command(), ""),
                new BaselineDockerStep("Baseline #2 · " + u.filePath(), u.command(), ""),
                new BaselineDockerStep("Baseline #3 · " + u.filePath(), u.command(), ""));
    }

    /**
     * Внутри цикла каждый раз {@code new Scanner(System.in)} — буфер предыдущего Scanner «съедает» stdin;
     * подаём пары токенов через pipe с паузой, чтобы к моменту {@code new Scanner} данные ещё шли в поток.
     */
    private static boolean needsPipedStdinForLoopBodyScanner(String src) {
        if (src == null || !src.contains("Scanner")) {
            return false;
        }
        if (!Pattern.compile("new\\s+Scanner\\s*\\(\\s*System\\.in\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(src).find()) {
            return false;
        }
        Pattern p = Pattern.compile(
                "(?:for|while)\\s*\\([^)]*\\)\\s*\\{[\\s\\S]{0,2000}?new\\s+Scanner\\s*\\(\\s*System\\.in",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(src).find();
    }

    private static String shStaggeredPairsToJava(String fqcn) {
        return "sh -c 'i=0; while [ $i -lt 48 ]; do echo Henrietta; echo 5; i=$((i+1)); sleep 0.06; done' | java -cp "
                + JAVA_PLATFORM_CLASS_DIR + " " + fqcn;
    }

    /**
     * Stdin для baseline по одному runnable: для Java — по исходнику только этого файла (а не всего workspace),
     * иначе эвристики по {@code parseInt(nextLine)} «ломаются» на соседних лабах в архиве.
     */
    private String baselineStdinForRunnable(Path workspace, RunnableUnit unit) {
        try {
            if (unit == null || !"java".equalsIgnoreCase(unit.language())) {
                return "";
            }
            String rel = unit.filePath();
            if (rel == null || rel.isBlank()) {
                return "";
            }
            Path p = workspace.resolve(rel).normalize();
            if (!p.startsWith(workspace) || !Files.isRegularFile(p)) {
                return "";
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);
            return capUtf16(buildDefaultJavaScannerStdinFromSource(src), MAX_STDIN_CHARS);
        } catch (Exception e) {
            return "";
        }
    }

    public ExecutionReport execute(AiCheckSourceType sourceType, String studentText, List<String> attachmentKeys, String gitUrl) {
        return execute(sourceType, studentText, attachmentKeys, gitUrl, CodeExecProgressSink.NOOP);
    }

    public record AiSuggestedTestCommand(
            String testName,
            String command,
            String dockerImage,
            /** Явный stdin или пусто — тогда для Java может быть выведен из шагов теста. */
            String stdin,
            List<String> steps
    ) {
        public AiSuggestedTestCommand(String testName, String command, String dockerImage) {
            this(testName, command, dockerImage, "", List.of());
        }
    }

    /**
     * Выполняет команды из ответа ИИ (recommended tests) по одной в workspace.
     */
    public ExecutionReport runAiSuggestedTestCommands(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl,
            List<AiSuggestedTestCommand> suggestions,
            CodeExecProgressSink sink
    ) {
        CodeExecProgressSink progress = sink == null ? CodeExecProgressSink.NOOP : sink;
        if (suggestions == null || suggestions.isEmpty()) {
            return new ExecutionReport("", List.of());
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("ai-check-ai-suggested-");
            progress.emit("AI_RECOMMENDED_MATERIALIZE", sourceType.name());
            switch (sourceType) {
                case CODE_ARCHIVE -> materializeArchive(workspace, attachmentKeys);
                case CODE_GIT -> materializeGit(workspace, gitUrl);
                case TEXT -> materializeTextSubmission(workspace, studentText, attachmentKeys);
                default -> {
                    return new ExecutionReport("Тип источника не поддерживает прогон команд", skippedSuggestions(suggestions));
                }
            }
            RuntimeLang lang = detectLanguage(workspace, studentText);
            List<ExecResult> results = new ArrayList<>();
            CodeExecProgressSink dockerPhrase = renameDockerSink(progress);

            boolean anyEmptyCmd = suggestions.stream()
                    .anyMatch(s -> s.command() == null || s.command().isBlank());

            List<ExecResult> languageFallback = List.of();
            if (anyEmptyCmd && lang != RuntimeLang.UNKNOWN) {
                languageFallback = runLanguagePipeline(workspace, lang, dockerPhrase);
                if (!languageFallback.isEmpty()) {
                    try {
                        progress.emit("AI_FALLBACK_PIPELINE", lang.label + "; steps=" + languageFallback.size());
                    } catch (Exception ignored) {
                    }
                }
            }

            int rrFallback = 0;
            for (AiSuggestedTestCommand sug : suggestions) {
                String name = suggestedTestDisplayName(sug, results.size());
                String cmdRaw = sanitizeModelShellCommand(sug.command());
                AiSuggestedTestCommand sugSanitized =
                        sanitizeSuggestionRecord(sug, cmdRaw);
                if (cmdRaw.isEmpty()) {
                    if (!languageFallback.isEmpty()) {
                        ExecResult fb = languageFallback.get(rrFallback % languageFallback.size());
                        rrFallback++;
                        String baseOut = fb.output().isBlank() ? "(нет вывода)" : fb.output();
                        String withNote = baseOut + "\n\n— Авто-прогон по языку проекта (поле command в ответе модели было пустым). "
                                + "Это не полноценный прогон сценария из описания теста.";
                        String status = mapEmptyCommandFallbackStatus(fb);
                        results.add(new ExecResult(name, fb.language(), fb.command(), status, fb.exitCode(),
                                trimOutput(withNote)));
                    } else {
                        results.add(new ExecResult(
                                name,
                                lang.label,
                                "",
                                "SKIPPED",
                                0,
                                "Модель не указала команду, а платформа не смогла подобрать общий автопрогон "
                                        + "(язык не распознан или проект без исполнимых файлов). "
                                        + "Допишите команду или опишите критерии подробнее."));
                    }
                    continue;
                }
                RuntimeLang execLang = effectiveLangForShellCommand(cmdRaw, lang);
                String cmdPrepared = prepareShellCommand(workspace, execLang, cmdRaw);
                String image = resolveDockerImageFromModelSuggestion(
                        sugSanitized.dockerImage(), workspace, execLang);
                progress.emit("AI_RECOMMENDED_TEST", name);
                String stdinPayload =
                        resolveStdinForAiTest(workspace, execLang, cmdPrepared, sugSanitized);
                results.add(runDocker(workspace, image, execLang.label, cmdPrepared, stdinPayload, dockerPhrase));
            }
            return new ExecutionReport(buildAiSuggestedSummary(results), results);
        } catch (Exception e) {
            log.warn("Прогон рекомендованных ИИ тестов: {}", e.getMessage());
            String msg = trimOutput(e.getMessage());
            List<ExecResult> failedMarks = new ArrayList<>();
            for (AiSuggestedTestCommand s : suggestions) {
                String nm = suggestedTestDisplayName(s, failedMarks.size());
                failedMarks.add(new ExecResult(nm, "unknown", "", "FAILED", -1,
                        "Ошибка подготовки workspace или прогона: " + msg));
            }
            return new ExecutionReport("Ошибка прогона рекомендованных тестов: " + msg, failedMarks);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static CodeExecProgressSink renameDockerSink(CodeExecProgressSink sink) {
        return (phase, detail) -> {
            if ("RUNTIME_DOCKER".equals(phase)) {
                sink.emit("AI_RECOMMENDED_TEST_CONTAINER", detail);
            } else {
                sink.emit(phase, detail);
            }
        };
    }

    private static String suggestedTestDisplayName(AiSuggestedTestCommand s, int index) {
        if (s.testName() != null && !s.testName().isBlank()) {
            return s.testName().trim();
        }
        return "test_" + (index + 1);
    }

    private List<ExecResult> skippedSuggestions(List<AiSuggestedTestCommand> suggestions) {
        List<ExecResult> out = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            AiSuggestedTestCommand s = suggestions.get(i);
            out.add(new ExecResult(suggestedTestDisplayName(s, i), "unknown", "", "SKIPPED", 0,
                    "Прогон недоступен для этого источника"));
        }
        return out;
    }

    private String defaultDockerImage(Path workspace, RuntimeLang lang) throws IOException {
        return switch (lang) {
            case PYTHON -> "python:3.12";
            case JAVA -> {
                if (Files.exists(workspace.resolve("pom.xml"))) {
                    yield "maven:3.9-eclipse-temurin-21";
                }
                if (Files.exists(workspace.resolve("gradlew")) || Files.exists(workspace.resolve("build.gradle"))
                        || Files.exists(workspace.resolve("build.gradle.kts"))) {
                    yield "gradle:8.10.2-jdk21";
                }
                yield "eclipse-temurin:21-jdk";
            }
            case KOTLIN -> "gradle:8.10.2-jdk21";
            case CPP -> "gcc:14";
            case CSHARP -> "mcr.microsoft.com/dotnet/sdk:8.0";
            case UNKNOWN -> "python:3.12";
        };
    }

    private String buildAiSuggestedSummary(List<ExecResult> results) {
        long pass = results.stream().filter(r -> "PASSED".equals(r.status())).count();
        long fail = results.stream().filter(r -> "FAILED".equals(r.status())).count();
        long tout = results.stream().filter(r -> "TIMEOUT".equals(r.status())).count();
        long skip = results.stream().filter(r -> "SKIPPED".equals(r.status())).count();
        return "ИИ-тесты: passed=" + pass + ", failed=" + fail + ", timeout=" + tout + ", skipped=" + skip;
    }

    public ExecutionReport execute(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl,
            CodeExecProgressSink sink
    ) {
        CodeExecProgressSink progress = sink == null ? CodeExecProgressSink.NOOP : sink;
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("ai-check-code-run-");
            progress.emit("RUNTIME_MATERIALIZE", sourceType.name());
            switch (sourceType) {
                case CODE_ARCHIVE -> materializeArchive(workspace, attachmentKeys);
                case CODE_GIT -> materializeGit(workspace, gitUrl);
                case TEXT -> materializeTextSubmission(workspace, studentText, attachmentKeys);
                default -> {
                    return new ExecutionReport("Источник не поддерживает runtime-проверку", List.of());
                }
            }
            RuntimeLang lang = detectLanguage(workspace, studentText);
            if (lang == RuntimeLang.UNKNOWN) {
                progress.emit("RUNTIME_LANG", "unknown");
                String msg = "Язык кода не удалось определить (поддерживаются: python/java/kotlin/c++/c#)";
                return new ExecutionReport(msg, List.of(new ExecResult(
                        "baseline-runtime",
                        "unknown",
                        "",
                        "SKIPPED",
                        0,
                        msg)));
            }
            progress.emit("RUNTIME_LANG", lang.label);
            List<ExecResult> results = runLanguagePipeline(workspace, lang, progress);
            return new ExecutionReport(buildSummary(lang, results), results);
        } catch (Exception e) {
            log.warn("Code runtime execution failed: {}", e.getMessage());
            return new ExecutionReport("Runtime-проверка не выполнена: " + e.getMessage(), List.of(
                    new ExecResult("runtime-bootstrap", "unknown", "init", "FAILED", -1, e.getMessage())
            ));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private Path materializeWorkspace(
            AiCheckSourceType sourceType,
            String studentText,
            List<String> attachmentKeys,
            String gitUrl
    ) throws IOException {
        Path workspace = Files.createTempDirectory("ai-check-workspace-");
        switch (sourceType) {
            case CODE_ARCHIVE -> materializeArchive(workspace, attachmentKeys);
            case CODE_GIT -> {
                try {
                    materializeGit(workspace, gitUrl);
                } catch (Exception e) {
                    throw new IOException("Не удалось материализовать git workspace: " + e.getMessage(), e);
                }
            }
            case TEXT -> materializeTextSubmission(workspace, studentText, attachmentKeys);
            default -> throw new IOException("Unsupported source type for workspace materialization");
        }
        return workspace;
    }

    private WorkspaceKind classifyWorkspace(Path workspace) throws IOException {
        if (workspace == null || !Files.exists(workspace)) {
            return WorkspaceKind.PROJECT;
        }
        List<Path> files = listFiles(workspace);
        boolean hasProjectMarkers = hasProjectMarkers(workspace, files);
        List<RunnableUnit> units = discoverRunnableUnits(workspace);
        boolean hasUnits = !units.isEmpty();
        if (hasProjectMarkers && hasUnits) {
            return WorkspaceKind.HYBRID;
        }
        if (hasProjectMarkers) {
            return WorkspaceKind.PROJECT;
        }
        return hasUnits ? WorkspaceKind.MINI_TASKS : WorkspaceKind.PROJECT;
    }

    private boolean hasProjectMarkers(Path workspace, List<Path> files) {
        if (Files.exists(workspace.resolve("pom.xml"))
                || Files.exists(workspace.resolve("gradlew"))
                || Files.exists(workspace.resolve("build.gradle"))
                || Files.exists(workspace.resolve("build.gradle.kts"))
                || Files.exists(workspace.resolve("package.json"))
                || Files.exists(workspace.resolve("CMakeLists.txt"))) {
            return true;
        }
        return files.stream().anyMatch(p -> {
            String s = p.toString().replace('\\', '/');
            return s.endsWith(".sln") || s.endsWith(".csproj");
        });
    }

    private List<RunnableUnit> discoverRunnableUnits(Path workspace) throws IOException {
        List<RunnableUnit> units = new ArrayList<>();
        List<Path> files = listFiles(workspace);
        for (Path p : files) {
            String rel = workspace.relativize(p).toString().replace('\\', '/');
            String low = rel.toLowerCase(Locale.ROOT);
            if (low.endsWith(".java") && !low.contains("/test/") && !low.endsWith("test.java")) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                String cls = javaLaunchClass(src, rel);
                if (cls != null && !cls.isBlank()) {
                    units.add(new RunnableUnit(
                            rel,
                            "java",
                            cls,
                            javacCompileAllSourcesToOutDir() + " && java -cp " + JAVA_PLATFORM_CLASS_DIR + " " + cls,
                            "eclipse-temurin:21-jdk"
                    ));
                }
            } else if (low.endsWith(".py") && !low.contains("/test/") && !low.startsWith("test_")) {
                units.add(new RunnableUnit(
                        rel,
                        "python",
                        rel,
                        "python \"" + rel + "\"",
                        "python:3.12"
                ));
            }
        }
        return units;
    }

    private static String javaLaunchClass(String source, String relativePath) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Pattern mainPat = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(", Pattern.DOTALL);
        if (!mainPat.matcher(source).find()) {
            return null;
        }
        Matcher pkg = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(source);
        String pkgName = pkg.find() ? pkg.group(1).trim() : "";
        Matcher pubClass = Pattern.compile("^\\s*public\\s+class\\s+([\\w$]+)\\b", Pattern.MULTILINE).matcher(source);
        Matcher pkgPrivClass = Pattern.compile("^\\s*class\\s+([\\w$]+)\\b", Pattern.MULTILINE).matcher(source);
        String cls = null;
        if (pubClass.find()) {
            cls = pubClass.group(1);
        } else if (pkgPrivClass.find()) {
            cls = pkgPrivClass.group(1);
        } else {
            String file = relativePath.replace('\\', '/');
            int slash = file.lastIndexOf('/');
            String base = slash >= 0 ? file.substring(slash + 1) : file;
            int dot = base.lastIndexOf('.');
            cls = dot > 0 ? base.substring(0, dot) : base;
        }
        return pkgName.isBlank() ? cls : pkgName + "." + cls;
    }

    private void materializeArchive(Path workspace, List<String> attachmentKeys) throws IOException {
        if (attachmentKeys == null || attachmentKeys.isEmpty()) {
            throw new IllegalArgumentException("Не приложен архив проекта");
        }
        String archiveKey = attachmentKeys.get(0);
        try (InputStream in = files.load(archiveKey).getInputStream();
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = workspace.resolve(entry.getName()).normalize();
                if (!out.startsWith(workspace)) {
                    throw new IOException("Подозрительный путь в архиве");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out);
                }
                zis.closeEntry();
            }
        }
    }

    private void materializeGit(Path workspace, String gitUrl) throws Exception {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Не указан URL репозитория");
        }
        try (Git ignored = Git.cloneRepository()
                .setURI(gitUrl.trim())
                .setDirectory(workspace.toFile())
                .setDepth(1)
                .setCloneAllBranches(false)
                .call()) {
            // cloned
        }
    }

    private void materializeTextSubmission(Path workspace, String studentText, List<String> attachmentKeys)
            throws IOException {
        if (attachmentKeys != null && attachmentKeys.size() == 1) {
            String key = attachmentKeys.get(0);
            if (isSingleCodeFileKey(key)) {
                materializeSingleCodeAttachment(workspace, key);
                return;
            }
        }
        materializeInlineCode(workspace, studentText);
    }

    private void materializeSingleCodeAttachment(Path workspace, String fileKey) throws IOException {
        try (InputStream in = files.load(fileKey).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            String filename = Path.of(fileKey).getFileName().toString();
            if (filename.toLowerCase(Locale.ROOT).endsWith(".java")) {
                Path rel = inlineJavaRelativePath(content);
                writeUnderWorkspace(workspace, rel, content);
                return;
            }
            Files.write(workspace.resolve(filename), bytes);
        }
    }

    private static boolean isSingleCodeFileKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".jsx") || lower.endsWith(".c") || lower.endsWith(".cpp")
                || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".cs")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".scala")
                || lower.endsWith(".rb") || lower.endsWith(".php") || lower.endsWith(".sql")
                || lower.endsWith(".sh") || lower.endsWith(".swift") || lower.endsWith(".m");
    }

    private void materializeInlineCode(Path workspace, String studentText) throws IOException {
        String content = stripCodeFence(extractCompilableSource(studentText));
        if (content.isBlank()) {
            throw new IllegalArgumentException("Не передан код для проверки");
        }
        RuntimeLang guessed = detectLanguage(workspace, content);
        String ext = switch (guessed) {
            case PYTHON -> ".py";
            case JAVA -> ".java";
            case KOTLIN -> ".kt";
            case CPP -> ".cpp";
            case CSHARP -> ".cs";
            default -> ".txt";
        };
        if (guessed == RuntimeLang.JAVA) {
            Path rel = inlineJavaRelativePath(content);
            writeUnderWorkspace(workspace, rel, content);
            return;
        }
        String fileName = switch (guessed) {
            case CSHARP -> "Program.cs";
            default -> "main" + ext;
        };
        Files.writeString(workspace.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    /** Путь вида Lab06/Pirogi.java чтобы javac принял {@code public class} при вставке кода текстом без архива. */
    private static Path inlineJavaRelativePath(String javaBody) {
        Pattern pkgPat = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Pattern pubClass = Pattern.compile("^\\s*public\\s+class\\s+([\\w$]+)\\b", Pattern.MULTILINE);
        Pattern plainClass = Pattern.compile("^\\s*class\\s+([\\w$]+)\\b", Pattern.MULTILINE);

        StringBuilder dir = new StringBuilder();
        Matcher mp = pkgPat.matcher(javaBody);
        if (mp.find()) {
            String cand = mp.group(1).trim();
            if (safeJavaFqnSegments(cand)) {
                dir.append(cand.replace('.', '/')).append('/');
            }
        }

        String simple = "Main";
        Matcher mPub = pubClass.matcher(javaBody);
        if (mPub.find()) {
            simple = mPub.group(1);
        } else {
            Matcher mPlain = plainClass.matcher(javaBody);
            if (mPlain.find()) {
                simple = mPlain.group(1);
            }
        }
        return Path.of(dir.toString() + simple + ".java");
    }

    /** Сегменты package — только допустимые идентификаторы, без traversal. */
    private static boolean safeJavaFqnSegments(String dotted) {
        if (dotted == null || dotted.isBlank()) {
            return false;
        }
        for (String seg : dotted.split("\\.")) {
            if (seg.isEmpty() || !seg.matches("[A-Za-z_]\\w*")) {
                return false;
            }
        }
        return true;
    }

    private static void writeUnderWorkspace(Path workspace, Path relativeFile, String content) throws IOException {
        Path dest = workspace.resolve(relativeFile).normalize();
        if (!dest.startsWith(workspace)) {
            throw new IOException("Недопустимый путь вставки файла");
        }
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, content, StandardCharsets.UTF_8);
    }

    private RuntimeLang detectLanguage(Path workspace, String studentText) {
        try {
            List<Path> tree = listFiles(workspace);
            long py = tree.stream().filter(p -> p.toString().endsWith(".py")).count();
            long java = tree.stream().filter(p -> p.toString().endsWith(".java")).count();
            long kt = tree.stream().filter(p -> p.toString().endsWith(".kt")).count();
            long cpp = tree.stream().filter(p -> p.toString().endsWith(".cpp") || p.toString().endsWith(".cc") || p.toString().endsWith(".cxx")).count();
            long cs = tree.stream().filter(p -> p.toString().endsWith(".cs")).count();
            // Сначала Java: один лишний .py не должен «перебивать» лабораторную на языке Java.
            if (java > 0) return RuntimeLang.JAVA;
            if (py > 0) return RuntimeLang.PYTHON;
            if (kt > 0) return RuntimeLang.KOTLIN;
            if (cpp > 0) return RuntimeLang.CPP;
            if (cs > 0) return RuntimeLang.CSHARP;
        } catch (Exception ignored) {
            // fallback to text heuristics
        }
        String text = studentText == null ? "" : studentText.toLowerCase(Locale.ROOT);
        if (text.contains("```java") || text.contains("public class")) return RuntimeLang.JAVA;
        if (text.contains("```python") || (text.contains("def ") && !text.contains("public class"))) return RuntimeLang.PYTHON;
        if (text.contains("```kotlin") || text.contains("fun main")) return RuntimeLang.KOTLIN;
        if (text.contains("```c++") || text.contains("#include") || text.contains("std::")) return RuntimeLang.CPP;
        if (text.contains("```c#") || text.contains("namespace ") || text.contains("using system")) return RuntimeLang.CSHARP;
        return RuntimeLang.UNKNOWN;
    }

    private RuntimeLang effectiveLangForShellCommand(String cmdRaw, RuntimeLang workspaceLang) {
        RuntimeLang hint = languageHintFromShellCommand(cmdRaw);
        return hint != RuntimeLang.UNKNOWN ? hint : workspaceLang;
    }

    /**
     * Подстраиваем язык образа Docker по тексту команды (модель могла указать {@code java ...}, пока дерево ошибочно
     * определилось как python).
     */
    private RuntimeLang languageHintFromShellCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return RuntimeLang.UNKNOWN;
        }
        String t = cmd.stripLeading();
        String low = cmd.toLowerCase(Locale.ROOT);
        if (t.startsWith("java ") || t.startsWith("javac ") || low.contains("mvn ")
                || low.contains("./gradlew") || low.contains("gradle ")
                || low.contains(" junit")) {
            return RuntimeLang.JAVA;
        }
        if (low.contains("kotlinc") || low.contains("kotlin ") || t.startsWith("ktlint")) {
            return RuntimeLang.KOTLIN;
        }
        if (low.contains("g++") || low.contains("gcc ") || low.contains("clang ")) {
            return RuntimeLang.CPP;
        }
        if (low.contains("dotnet ") || low.startsWith("csc ")) {
            return RuntimeLang.CSHARP;
        }
        if (t.startsWith("python ") || low.contains("pytest ") || low.contains("pip ")) {
            return RuntimeLang.PYTHON;
        }
        return RuntimeLang.UNKNOWN;
    }

    private String prepareShellCommand(Path workspace, RuntimeLang execLang, String cmdRaw) {
        try {
            String s = cmdRaw == null ? "" : cmdRaw.trim();
            if (s.isEmpty() || execLang != RuntimeLang.JAVA) {
                return s;
            }
            if (s.contains("javac") || s.startsWith("mvn ") || s.contains("./gradlew") || s.startsWith("gradle ")) {
                return s;
            }
            return normalizeJavaStudentCommand(workspace, s);
        } catch (IOException e) {
            log.warn("prepareShellCommand: {}", e.getMessage());
            return cmdRaw.trim();
        }
    }

    private record JavaFqcnMeta(String fqcn, String simple) {
    }

    private List<JavaFqcnMeta> listPublicJavaClasses(Path workspace) throws IOException {
        List<JavaFqcnMeta> out = new ArrayList<>();
        Pattern pkg = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
        Pattern cls = Pattern.compile("^\\s*(?:public\\s+)?class\\s+(\\w+)\\b", Pattern.MULTILINE);
        for (Path p : listFiles(workspace)) {
            String pathStr = p.toString().replace('\\', '/');
            if (!pathStr.endsWith(".java") || pathStr.contains("/target/")) {
                continue;
            }
            String txt = Files.readString(p, StandardCharsets.UTF_8);
            Matcher mp = pkg.matcher(txt);
            String pack = "";
            if (mp.find()) {
                pack = mp.group(1);
            }
            Matcher mc = cls.matcher(txt);
            if (!mc.find()) {
                continue;
            }
            String simp = mc.group(1);
            String fqcn = pack.isEmpty() ? simp : pack + "." + simp;
            out.add(new JavaFqcnMeta(fqcn, simp));
        }
        return out;
    }

    /**
     * Находит main-класс по простому имени {@code Pirogi} или оставляет FQN {@code Lab06.Pirogi}.
     */
    private String resolveJavaMainFqcn(Path workspace, String mainToken) throws IOException {
        String mt = mainToken.trim();
        List<JavaFqcnMeta> types = listPublicJavaClasses(workspace);
        if (mt.contains(".")) {
            boolean exactExists = types.stream().anyMatch(m -> m.fqcn().equals(mt));
            if (exactExists) {
                return mt;
            }
            int dot = mt.lastIndexOf('.');
            String simple = dot >= 0 ? mt.substring(dot + 1) : mt;
            Optional<String> bySimple = types.stream()
                    .filter(m -> m.simple().equals(simple))
                    .map(JavaFqcnMeta::fqcn)
                    .findFirst();
            return bySimple.orElse(mt);
        }
        Optional<String> direct = types.stream()
                .filter(m -> m.simple().equals(mt))
                .map(JavaFqcnMeta::fqcn)
                .findFirst();
        return direct.orElse(mt);
    }

    /**
     * Каталог для скомпилированных классов внутри workspace (удаляется перед javac).
     * Без {@code -d javac} кладёт .class рядом с .java (например {@code src/Lab08/}), тогда
     * {@code java -cp . pkg.Main} не находит класс — отсюда ClassNotFoundException.
     */
    private static final String JAVA_PLATFORM_CLASS_DIR = "_platform_jclasses";

    private static String javacCompileAllSourcesToOutDir() {
        return "rm -rf " + JAVA_PLATFORM_CLASS_DIR
                + " && mkdir -p " + JAVA_PLATFORM_CLASS_DIR
                + " && javac -encoding UTF-8 -d " + JAVA_PLATFORM_CLASS_DIR
                + " $(find . -name '*.java' -not -path '*/target/*' -not -path './" + JAVA_PLATFORM_CLASS_DIR + "/*' | sort -u | tr '\\n' ' ')";
    }

    /**
     * Модели часто пишут {@code java Pirogi} при пакете {@code Lab06} — добавляем компиляцию в {@code _platform_jclasses}
     * и запуск {@code java -cp _platform_jclasses Lab06.Pirogi}.
     */
    private String normalizeJavaStudentCommand(Path workspace, String original) throws IOException {
        String s = original.strip();
        if (s.isEmpty() || s.contains("javac")) {
            return s;
        }
        List<String> tok = Arrays.stream(s.split("\\s+")).filter(x -> !x.isBlank()).toList();
        if (tok.size() < 2 || !"java".equals(tok.getFirst())) {
            return s;
        }

        int i = 1;
        String classpath = JAVA_PLATFORM_CLASS_DIR;
        while (i < tok.size()) {
            String w = tok.get(i);
            if ("-cp".equals(w) || "-classpath".equals(w)) {
                if (i + 1 < tok.size()) {
                    String modelCp = tok.get(i + 1);
                    classpath = JAVA_PLATFORM_CLASS_DIR + ":" + modelCp;
                    i += 2;
                    continue;
                }
                break;
            }
            if (w.startsWith("-")) {
                i++;
                continue;
            }
            break;
        }

        if (i >= tok.size()) {
            return s;
        }

        StringBuilder tail = new StringBuilder();
        for (int j = i + 1; j < tok.size(); j++) {
            if (!tail.isEmpty()) {
                tail.append(' ');
            }
            tail.append(tok.get(j));
        }

        String mainToken = tok.get(i);
        String fqcn = resolveJavaMainFqcn(workspace, mainToken);

        boolean quoteCp = classpath.chars().anyMatch(ch -> ch == ' ');
        String cpArg = quoteCp ? ("'" + classpath.replace("'", "'\"'\"'") + "'") : classpath;

        String tailArgs = tail.toString().stripTrailing();
        String javaCmd = "java -cp " + cpArg + " " + fqcn + (tailArgs.isEmpty() ? "" : " " + tailArgs);
        javaCmd = javaCmd.trim();

        return javacCompileAllSourcesToOutDir() + " && " + javaCmd;
    }

    /**
     * Stdin для автопрогона: поле модели, иначе эвристика для Java+Scanner по шагам теста (команды в кавычках / через запятую).
     */
    private String resolveStdinForAiTest(Path workspace, RuntimeLang execLang, String preparedCmd, AiSuggestedTestCommand sug) throws IOException {
        String explicit = sug.stdin() == null ? "" : sug.stdin().trim();
        if (!explicit.isEmpty()) {
            return capUtf16(unescapeJsonStdinEscapes(explicit), MAX_STDIN_CHARS);
        }
        if (execLang != RuntimeLang.JAVA || shellCommandAlreadyFeedsStdin(preparedCmd)) {
            return "";
        }
        List<String> st = sug.steps();
        if (st == null || st.isEmpty()) {
            return capUtf16(buildDefaultJavaScannerStdin(workspace), MAX_STDIN_CHARS);
        }
        String inferred = buildJavaStdinFromStepsAndWorkspace(workspace, st);
        if (inferred == null || inferred.isBlank()) {
            inferred = buildDefaultJavaScannerStdin(workspace);
        }
        return capUtf16(inferred, MAX_STDIN_CHARS);
    }

    /**
     * В JSON модель часто отдаёт литералы {@code \n}; после Jackson они уже переводы строк — оставляем как есть.
     */
    private static String unescapeJsonStdinEscapes(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }

    private boolean shellCommandAlreadyFeedsStdin(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return false;
        }
        String c = cmd;
        String low = c.toLowerCase(Locale.ROOT);
        if (low.contains("<<") || low.contains("< /") || low.contains("</")) {
            return true;
        }
        if (low.contains("|") && (low.contains("printf ") || low.contains("/bin/printf ")
                || low.contains("echo ") || low.contains("/bin/echo ") || low.contains("cat ")
                || low.contains("yes ") || low.contains("/dev/null"))) {
            return true;
        }
        return false;
    }

    private String buildJavaStdinFromStepsAndWorkspace(Path workspace, List<String> steps) throws IOException {
        String javaSrc = concatenateJavaSources(workspace);
        if (!javaSrc.contains("Scanner")) {
            return "";
        }
        boolean parseIntFirst = Pattern.compile("parseInt\\s*\\(\\s*[^)]*nextLine\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(javaSrc).find();
        List<String> tokens = extractInteractiveCommandTokens(steps);
        tokens = maybeExpandSingletonIngredientBakePairs(tokens, parseIntFirst);
        tokens = expandPreambleBeforeEachConsecutiveBake(tokens, parseIntFirst);
        if (tokens.isEmpty()) {
            return "";
        }
        if (looksLikeMatrixDimsThenFlatLineInput(javaSrc)) {
            String matrixStdin = repackStdinForMatrixDimsFlatLine(tokens);
            if (matrixStdin != null && !matrixStdin.isBlank()) {
                return padJavaScannerStdinIfNeeded(javaSrc, matrixStdin);
            }
            String partialMatrixStdin = repackStdinForMatrixDimsFlatLinePartial(tokens);
            if (partialMatrixStdin != null && !partialMatrixStdin.isBlank()) {
                return padJavaScannerStdinIfNeeded(javaSrc, partialMatrixStdin);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (parseIntFirst && !tokens.getFirst().matches("^-?\\d+$")) {
            sb.append("50\n");
        }
        for (String t : tokens) {
            sb.append(t).append('\n');
        }
        return padJavaScannerStdinIfNeeded(javaSrc, sb.toString());
    }

    /**
     * Защитный stdin, когда модель не дала ни stdin, ни steps, а код ждёт Scanner.nextLine():
     * стараемся отдать хотя бы корректный минимум строк, чтобы не ловить NoSuchElementException "из пустоты".
     */
    private String buildDefaultJavaScannerStdin(Path workspace) throws IOException {
        return buildDefaultJavaScannerStdinFromSource(concatenateJavaSources(workspace));
    }

    /**
     * То же, но по одному файлу — нужно для baseline по runnable и чтобы не смешивать соседние лабы в архиве.
     */
    private String buildDefaultJavaScannerStdinFromSource(String javaSrc) {
        if (javaSrc == null || javaSrc.isBlank() || !javaSrc.contains("Scanner")) {
            return "";
        }
        // Ложные срабатывания parse* в комментариях ломают ветку «строковый nextLine» (например Chicken).
        String heur = javaSourceStrippedOfCommentsForHeuristics(javaSrc);

        if (looksLikeStopOnFirstReadLineLoop(heur)) {
            return padJavaScannerStdinIfNeeded(javaSrc, "stop\n");
        }
        if (looksLikeTravelBudgetEndLoop(heur)) {
            return padJavaScannerStdinIfNeeded(javaSrc, "spain\n5\n5\nend\n");
        }
        if (looksLikeScannerNextThenParseIntOnNext(heur)
                || (Pattern.compile("new\\s+Chicken\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(heur).find()
                        && heur.contains("parseInt")
                        && heur.contains(".next("))) {
            return padJavaScannerStdinIfNeeded(javaSrc, repeatLineToken("Henrietta 5", 40));
        }

        int parseIntNextLineCount = countParseIntNextLine(heur);
        int nextLineCalls = countScannerNextLineCalls(heur);
        int needLines = Math.max(parseIntNextLineCount, nextLineCalls);

        int nextInts = countScannerTokenCalls(heur, "nextInt");
        int nextLongs = countScannerTokenCalls(heur, "nextLong");
        int nextBytes = countScannerTokenCalls(heur, "nextByte");
        int nextShorts = countScannerTokenCalls(heur, "nextShort");
        int nextDoubles = countScannerTokenCalls(heur, "nextDouble");
        int nextFloats = countScannerTokenCalls(heur, "nextFloat");
        int nextBools = countScannerTokenCalls(heur, "nextBoolean");
        int genericNext = countScannerPlainNextCalls(heur);
        int numericReads = nextInts + nextLongs + nextBytes + nextShorts + nextDoubles + nextFloats + nextBools;

        if (looksLikeMatrixDimsThenFlatLineInput(heur)) {
            String flat = String.join(" ", java.util.Collections.nCopies(32, "0"));
            return padJavaScannerStdinIfNeeded(javaSrc, "2\n2\n" + flat + "\n");
        }

        /*
         * Integer.parseInt(sc.nextLine()) / Double.parseDouble(...) — вся строка должна быть одним числом.
         * Сетка "0 0 0 ..." на строку даёт NumberFormatException при parse* на целой строке.
         */
        int wholeLineNumericParses = countNumericParseOnFullNextLine(heur);
        /*
         * int n = Integer.parseInt(line); где line = scanner.nextLine() — parse* не оборачивает nextLine(),
         * но строка всё равно должна быть одним числом.
         * Эвристика «возможная матрица через переменные»: ≥2 таких parse + split + много nextLine — оставляем сетку (редкий случай).
         */
        int identBoundParses = countNumericParseSimpleIdentifierArg(heur);
        boolean maybeVariableMatrix =
                heur.contains(".split") && identBoundParses >= 2 && nextLineCalls >= 3;
        if (wholeLineNumericParses > 0 || (identBoundParses > 0 && nextLineCalls > 0 && !maybeVariableMatrix)) {
            int lines = Math.max(wholeLineNumericParses, needLines);
            lines = Math.max(lines, identBoundParses);
            lines = Math.max(lines, Math.min(40, numericReads + 4));
            if (genericNext > 0) {
                lines = Math.max(lines, Math.min(40, genericNext + 4));
            }
            lines = Math.max(lines, SCANNER_BASELINE_MIN_SINGLETON_LINES);
            StringBuilder onePer = new StringBuilder();
            for (int i = 0; i < lines; i++) {
                onePer.append(numericBaselineLineToken(heur, i)).append('\n');
            }
            return padJavaScannerStdinIfNeeded(javaSrc, onePer.toString());
        }

        boolean plainNextLineBaseline = nextLineCalls > 0
                && wholeLineNumericParses == 0
                && identBoundParses == 0
                && numericReads == 0
                && genericNext == 0
                && !maybeVariableMatrix;
        if (plainNextLineBaseline) {
            int lines = Math.max(nextLineCalls, SCANNER_BASELINE_MIN_SINGLETON_LINES);
            lines = Math.min(900, lines);
            String token = defaultPlainNextLineStringToken(javaSrc, heur);
            StringBuilder textLines = new StringBuilder();
            for (int i = 0; i < lines; i++) {
                textLines.append(token).append('\n');
            }
            return padJavaScannerStdinIfNeeded(javaSrc, textLines.toString());
        }

        int tokensPerLine = Math.min(48, Math.max(8, numericReads + 4));
        int lineCount = Math.min(40, Math.max(needLines, 2));
        if (numericReads > 0) {
            lineCount = Math.max(lineCount, Math.min(40, (numericReads + tokensPerLine - 1) / tokensPerLine + 4));
            lineCount = Math.max(lineCount, Math.min(40, genericNext + 4));
        } else if (genericNext > 0) {
            lineCount = Math.max(lineCount, Math.min(40, genericNext + 4));
        }

        StringBuilder block = new StringBuilder();
        int tokIdx = 0;
        for (int r = 0; r < lineCount; r++) {
            for (int c = 0; c < tokensPerLine; c++) {
                if (c > 0) {
                    block.append(' ');
                }
                block.append(numericBaselineLineToken(heur, tokIdx++));
            }
            block.append('\n');
        }
        return padJavaScannerStdinIfNeeded(javaSrc, block.toString());
    }

    /** Lab04: {@code while(true)} → {@code nextLine}, сразу {@code "stop"} выходим без {@code parseInt}. */
    private static boolean looksLikeStopOnFirstReadLineLoop(String h) {
        if (!Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(h).find()) {
            return false;
        }
        if (!h.toLowerCase(Locale.ROOT).contains("\"stop\"")) {
            return false;
        }
        if (!h.contains("parseInt")) {
            return false;
        }
        return Pattern.compile(
                        "nextLine\\s*\\(\\s*\\)[\\s\\S]{0,800}?equals\\s*\\(\\s*\"stop\"",
                        Pattern.CASE_INSENSITIVE)
                .matcher(h)
                .find();
    }

    /** Lab03: бюджет поездок — внешний цикл завершается по {@code end}, нужен конечный сценарий, а не бесконечные числа. */
    private static boolean looksLikeTravelBudgetEndLoop(String h) {
        if (!Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(h).find()) {
            return false;
        }
        if (!h.contains("\"end\"")) {
            return false;
        }
        if (!h.contains("nextLine")) {
            return false;
        }
        if (looksLikeStopOnFirstReadLineLoop(h)) {
            return false;
        }
        return h.contains("balance") || h.contains("requiredsum") || h.contains("income");
    }

    /** {@code .next(), Integer.parseInt(sc.next())} — токены по строкам, не {@code nextLine}. */
    private static boolean looksLikeScannerNextThenParseIntOnNext(String h) {
        return Pattern.compile(
                        "\\.\\s*next\\s*\\(\\s*\\)\\s*,\\s*[^;\\)]*parse(?:Int|Long|Double|Float)\\s*\\(\\s*[^\\)]*\\.\\s*next\\s*\\(\\s*\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(h)
                .find();
    }

    private static String repeatLineToken(String lineWithoutNewline, int count) {
        StringBuilder sb = new StringBuilder(lineWithoutNewline.length() * count + count);
        for (int i = 0; i < count; i++) {
            sb.append(lineWithoutNewline).append('\n');
        }
        return sb.toString();
    }

    private static String javaSourceStrippedOfCommentsForHeuristics(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder noBlock = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            if (i < raw.length() - 1 && raw.charAt(i) == '/' && raw.charAt(i + 1) == '*') {
                int end = raw.indexOf("*/", i + 2);
                if (end < 0) {
                    noBlock.append(raw.substring(i));
                    break;
                }
                noBlock.append(' ');
                i = end + 2;
                continue;
            }
            noBlock.append(raw.charAt(i));
            i++;
        }
        String s = noBlock.toString();
        String[] lines = s.split("\\R", -1);
        StringBuilder out = new StringBuilder(s.length());
        for (String line : lines) {
            out.append(stripJavaLineSlashCommentOutsideStrings(line)).append('\n');
        }
        return out.toString();
    }

    private static String stripJavaLineSlashCommentOutsideStrings(String line) {
        boolean inStr = false;
        boolean esc = false;
        for (int j = 0; j < line.length() - 1; j++) {
            char c = line.charAt(j);
            if (esc) {
                esc = false;
                continue;
            }
            if (inStr) {
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '/' && line.charAt(j + 1) == '/') {
                return line.substring(0, j);
            }
        }
        return line;
    }

    private static final int[] BASELINE_PRIME_LINE_VALUES =
            {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47};

    private static boolean javaLooksLikePrimeExercise(String heurSrc) {
        if (heurSrc == null) {
            return false;
        }
        String h = heurSrc.toLowerCase(Locale.ROOT);
        return h.contains("prime") || h.contains("прост");
    }

    /** Одна строка stdin с одним целым: для лаб про простые числа — не 0/1, а ротация малых простых. */
    private static String numericBaselineLineToken(String heurSrc, int index) {
        if (javaLooksLikePrimeExercise(heurSrc)) {
            return Integer.toString(BASELINE_PRIME_LINE_VALUES[index % BASELINE_PRIME_LINE_VALUES.length]);
        }
        return "2";
    }

    /** Вызовы {@code .next()} у Scanner (не {@code nextLine}, {@code nextInt}, … — у них после {@code next} идут буквы до скобки). */
    private static int countScannerPlainNextCalls(String javaSrc) {
        Matcher m = Pattern.compile("\\.\\s*next\\s*\\(\\s*\\)").matcher(javaSrc);
        int c = 0;
        while (m.find()) {
            c++;
            if (c >= 64) {
                break;
            }
        }
        return c;
    }

    private static int countScannerNextLineCalls(String javaSrc) {
        Matcher m = Pattern.compile("\\.nextLine\\s*\\(\\s*\\)").matcher(javaSrc);
        int c = 0;
        while (m.find()) {
            c++;
            if (c >= 64) {
                break;
            }
        }
        return c;
    }

    private static int countScannerTokenCalls(String javaSrc, String method) {
        String pat = "\\.\\s*" + Pattern.quote(method) + "\\s*\\(\\s*\\)";
        Matcher m = Pattern.compile(pat).matcher(javaSrc);
        int c = 0;
        while (m.find()) {
            c++;
            if (c >= 64) {
                break;
            }
        }
        return c;
    }

    private static int countParseIntNextLine(String javaSrc) {
        Matcher m = Pattern.compile(
                        "parseInt\\s*\\(\\s*[^)]*nextLine\\s*\\(\\s*\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(javaSrc);
        int c = 0;
        while (m.find()) {
            c++;
            if (c >= 8) {
                break;
            }
        }
        return c;
    }

    /**
     * Сколько раз в коде встречается разбор целой строки в число через {@code parseInt}/{@code parseLong}/{@code parseDouble}/{@code parseFloat}(… {@code nextLine()} …).
     */
    private static int countNumericParseOnFullNextLine(String javaSrc) {
        if (javaSrc == null || !javaSrc.contains("nextLine")) {
            return 0;
        }
        int sum = 0;
        String[] methods = {"parseInt", "parseLong", "parseDouble", "parseFloat"};
        for (String meth : methods) {
            Matcher m = Pattern.compile(
                            Pattern.quote(meth) + "\\s*\\(\\s*[^)]*nextLine\\s*\\(\\s*\\)",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(javaSrc);
            while (m.find()) {
                sum++;
                if (sum >= 64) {
                    return sum;
                }
            }
        }
        return sum;
    }

    /**
     * {@code parseInt(x)} / {@code parseDouble(x)} с единственным аргументом-идентификатором (часто строка из {@code nextLine}).
     */
    private static int countNumericParseSimpleIdentifierArg(String javaSrc) {
        if (javaSrc == null || javaSrc.isBlank()) {
            return 0;
        }
        int sum = 0;
        String[] methods = {"parseInt", "parseLong", "parseDouble", "parseFloat"};
        for (String meth : methods) {
            Matcher m = Pattern.compile(
                            Pattern.quote(meth) + "\\s*\\(\\s*[A-Za-z_][\\w]*\\s*\\)",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(javaSrc);
            while (m.find()) {
                sum++;
                if (sum >= 64) {
                    return sum;
                }
            }
        }
        return sum;
    }

    /**
     * Паттерн лабораторных: два размерa через {@code parseInt(... nextLine())}, затем одна строка с {@code split} по всей матрице.
     * Модель кладёт в steps подряд n, m и n·m чисел — разворачиваем в три строки stdin, чтобы не ломался {@code nextLine}.
     */
    private static boolean looksLikeMatrixDimsThenFlatLineInput(String javaSrc) {
        Matcher splitM = Pattern.compile(
                        "nextLine\\s*\\([^)]*\\)\\s*(?:\\.\\s*trim\\s*\\(\\s*\\)\\s*)?\\.\\s*split",
                        Pattern.CASE_INSENSITIVE)
                .matcher(javaSrc);
        if (!splitM.find()) {
            return false;
        }
        int cap = splitM.start();
        String head = javaSrc.substring(0, cap);
        Pattern pIntLine = Pattern.compile(
                "parseInt\\s*\\(\\s*[^)]*nextLine\\s*\\(\\s*\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        int count = 0;
        Matcher mi = pIntLine.matcher(head);
        while (mi.find()) {
            count++;
            if (count >= 3) {
                break;
            }
        }
        return count >= 2;
    }

    /**
     * @return {@code null} если токены не задают два целых размерa и не хватает n·m элементов матрицы
     */
    private static String repackStdinForMatrixDimsFlatLine(List<String> tokens) {
        if (tokens == null || tokens.size() < 2) {
            return null;
        }
        try {
            int n = Integer.parseInt(tokens.get(0));
            int m = Integer.parseInt(tokens.get(1));
            if (n < 0 || m < 0 || n > 4096 || m > 4096) {
                return null;
            }
            long needL = (long) n * (long) m;
            if (needL > MAX_EXTRACTED_STDIN_TOKENS) {
                return null;
            }
            int need = (int) needL;
            if (tokens.size() < 2 + need) {
                return null;
            }
            StringBuilder flat = new StringBuilder();
            for (int i = 0; i < need; i++) {
                if (i > 0) {
                    flat.append(' ');
                }
                flat.append(tokens.get(2 + i).trim());
            }
            return tokens.get(0) + "\n" + tokens.get(1) + "\n" + flat + "\n";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Негативные сценарии: n и m заданы, а чисел для матрицы меньше (или больше) n·m — всё равно одна третья строка
     * со всеми переданными значениями, иначе fallback «число на строку» ломает {@code nextLine().split}.
     */
    private static String repackStdinForMatrixDimsFlatLinePartial(List<String> tokens) {
        if (tokens == null || tokens.size() < 2) {
            return null;
        }
        try {
            Integer.parseInt(tokens.get(0));
            Integer.parseInt(tokens.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String n = tokens.get(0).trim();
        String m = tokens.get(1).trim();
        if (tokens.size() == 2) {
            return n + "\n" + m + "\n\n";
        }
        StringBuilder flat = new StringBuilder();
        for (int i = 2; i < tokens.size(); i++) {
            if (i > 2) {
                flat.append(' ');
            }
            flat.append(tokens.get(i).trim());
        }
        return n + "\n" + m + "\n" + flat + "\n";
    }

    /**
     * Модель часто пишет шаги строками «2 flour bake», «eggs bake», «shugar bake» — по смыслу перед каждой
     * выпечкой нужен полный набор ингредиентов. Разворачиваем в {@code N × (ing1 ing2 … bake)}.
     */
    private static List<String> maybeExpandSingletonIngredientBakePairs(List<String> tokens, boolean parseIntFirst) {
        if (!parseIntFirst || tokens == null || tokens.size() < 4) {
            return tokens;
        }
        String headTok = tokens.getFirst();
        if (!headTok.matches("^-?\\d+$")) {
            return tokens;
        }
        int targetBakes;
        try {
            targetBakes = Integer.parseInt(headTok);
        } catch (NumberFormatException e) {
            return tokens;
        }
        if (targetBakes < 1) {
            return tokens;
        }
        List<String> rest = new ArrayList<>(tokens.subList(1, tokens.size()));
        List<String> wordsInOrder = new ArrayList<>();
        int i = 0;
        while (i + 1 < rest.size()) {
            String w = rest.get(i);
            String b = rest.get(i + 1);
            if ("bake".equals(b) && !"bake".equals(w) && isLabStdinToken(w)) {
                wordsInOrder.add(w);
                i += 2;
            } else {
                return tokens;
            }
        }
        if (i != rest.size() || wordsInOrder.isEmpty()) {
            return tokens;
        }
        int cycles = Math.min(targetBakes, 48);
        List<String> out = new ArrayList<>(1 + (wordsInOrder.size() + 1) * cycles);
        out.add(headTok);
        for (int c = 0; c < cycles; c++) {
            out.addAll(wordsInOrder);
            out.add("bake");
        }
        return out;
    }

    private static String repeatLinesUpToBudget(String lineBody, int maxLines, int budgetChars) {
        if (budgetChars <= 0 || maxLines <= 0 || lineBody == null || lineBody.isBlank()) {
            return "";
        }
        String one = lineBody.endsWith("\n") ? lineBody : lineBody + "\n";
        int per = one.length();
        int n = Math.min(maxLines, budgetChars / Math.max(1, per));
        StringBuilder sb = new StringBuilder(n * per);
        for (int i = 0; i < n; i++) {
            sb.append(one);
        }
        return sb.toString();
    }

    private static String defaultPlainNextLineStringToken(String rawSrc, String heurSrc) {
        String s = rawSrc == null ? "" : rawSrc;
        String h = heurSrc == null ? "" : heurSrc;
        if (s.contains("Chicken")
                || h.contains("Chicken")
                || Pattern.compile("new\\s+Chicken\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(s).find()) {
            return "Henrietta";
        }
        return "Student";
    }

    /**
     * Короткие case-литералы вроде {@code "xD"} не подмешиваем в stdin (ломают валидацию имён и т.п.).
     */
    private static boolean isSafeSwitchPadLiteral(String low) {
        if (low == null || low.isEmpty()) {
            return false;
        }
        if (low.matches("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")) {
            return true;
        }
        if (SWITCH_PAD_SHORT_OK.contains(low)) {
            return true;
        }
        return low.length() >= 4 && low.matches("[a-z][a-z0-9_-]*");
    }

    private static final Set<String> SWITCH_PAD_SHORT_OK = Set.of(
            "yes", "no", "up", "go", "on", "off", "ok",
            "end", "run", "bye", "add", "new", "mix", "bake",
            "quit", "exit", "stop", "help", "menu", "back",
            "cat", "dog", "day", "egg", "one", "two", "out", "red", "blue");

    /**
     * Для цикла {@code while (true)} без {@code hasNextLine} после сценария модели часто остаётся чтение при
     * незакрытом счётчике — добавляем циклический «мусорный» ввод из строковых case-literal, чтобы не получать
     * {@link java.util.NoSuchElementException} (программа сама выйдет по break или упрётся в лимит runner).
     */
    private static String padJavaScannerStdinIfNeeded(String javaSrc, String stdinCore) {
        if (stdinCore == null || stdinCore.isBlank() || javaSrc == null) {
            return stdinCore == null ? "" : stdinCore;
        }
        if (!looksLikeInfiniteScannerLoopRisk(javaSrc)) {
            return stdinCore;
        }
        List<String> cycle = orderedSwitchLiteralsSuitedForStdin(javaSrc);
        int budget = Math.max(0, MAX_STDIN_CHARS - stdinCore.length() - 64);
        if (budget <= 0) {
            return stdinCore;
        }
        if (cycle.isEmpty()) {
            String heurPad = javaSourceStrippedOfCommentsForHeuristics(javaSrc);
            StringBuilder extra = new StringBuilder();
            int lim = Math.min(SCANNER_EOF_PAD_MAX_LINES, 480);
            for (int pi = 0; pi < lim && extra.length() < budget; pi++) {
                String line = numericBaselineLineToken(heurPad, pi) + "\n";
                if (extra.length() + line.length() > budget) {
                    break;
                }
                extra.append(line);
            }
            return stdinCore + extra;
        }
        StringBuilder pad = new StringBuilder();
        int lines = 0;
        int idx = 0;
        while (lines < SCANNER_EOF_PAD_MAX_LINES && pad.length() < budget) {
            pad.append(cycle.get(idx % cycle.size())).append('\n');
            idx++;
            lines++;
        }
        return stdinCore + pad;
    }

    private static boolean looksLikeInfiniteScannerLoopRisk(String javaSrc) {
        String h = javaSourceStrippedOfCommentsForHeuristics(javaSrc);
        if (!h.contains("Scanner")) {
            return false;
        }
        if (h.contains("hasNextLine()")) {
            return false;
        }
        if (h.contains("hasNext()")) {
            return false;
        }
        if (Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)").matcher(h).find()) {
            return true;
        }
        return h.contains(".nextLine(") && Pattern.compile("\\bwhile\\s*\\(").matcher(h).find();
    }

    private static List<String> orderedSwitchLiteralsSuitedForStdin(String javaSrc) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Pattern[] ps = {
                Pattern.compile("case\\s*\\(\\s*\"([^\"\\\\]{1,80})\"\\s*\\)"),
                Pattern.compile("case\\s+\"([^\"\\\\]{1,80})\""),
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(javaSrc);
            while (m.find()) {
                String lit = m.group(1).trim().toLowerCase(Locale.ROOT);
                if (isLabStdinToken(lit) && isSafeSwitchPadLiteral(lit)) {
                    seen.add(lit);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private AiSuggestedTestCommand sanitizeSuggestionRecord(AiSuggestedTestCommand s, String cmdSanitized) {
        return new AiSuggestedTestCommand(
                s.testName(),
                cmdSanitized,
                normalizeDockerFieldSingleLine(s.dockerImage()),
                s.stdin() == null ? "" : stripBinaryControlKeepingTextWhitespace(s.stdin()),
                sanitizeModelTestSteps(s.steps())
        );
    }

    private static String sanitizeModelShellCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.stripLeading();
        if (t.startsWith("\uFEFF")) {
            t = t.substring(1).trim();
        }
        String inner = extractFirstMarkdownFenceInterior(t);
        if (!inner.isEmpty()) {
            t = inner;
        } else if (t.startsWith("```")) {
            t = stripCodeFence(t);
        }
        t = stripBinaryControlKeepingTextWhitespace(t);
        if (t.length() > MAX_MODEL_SHELL_COMMAND_CHARS) {
            t = t.substring(0, MAX_MODEL_SHELL_COMMAND_CHARS) + "\n# ...truncated-model-command...";
        }
        return t.trim();
    }

    private static String extractFirstMarkdownFenceInterior(String text) {
        Matcher m = Pattern.compile("(?s)```(?:[a-zA-Z0-9+.-]*)?\\s*\\r?\\n(.*?)\\r?\\n```").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static String normalizeDockerFieldSingleLine(String dockerImage) {
        if (dockerImage == null) {
            return "";
        }
        String t = dockerImage.strip();
        t = stripBinaryControlKeepingTextWhitespace(t.replace('\r', '\n'));
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl).trim();
        }
        return t;
    }

    private String resolveDockerImageFromModelSuggestion(
            String dockerField, Path workspace, RuntimeLang execLang
    ) throws IOException {
        String t = dockerField == null ? "" : dockerField.trim();
        if (t.isEmpty()) {
            return defaultDockerImage(workspace, execLang);
        }
        String remapped = remapLegacyJvmDockerImageFromModel(t, execLang);
        if (!remapped.equals(t)) {
            log.info("docker_image модели заменён: {} → {}", abbrevForLog(replaceInvisible(t), 96), remapped);
        }
        t = remapped;
        if (!looksLikeSafeOciImageReference(t)) {
            log.warn("Игнорируется docker_image модели (подозрительный вид): {}",
                    abbrevForLog(replaceInvisible(t), 120));
            return defaultDockerImage(workspace, execLang);
        }
        return t;
    }

    /**
     * Модели часто советуют {@code openjdk:17}: на Docker Hub тег часто недоступен (manifest unknown).
     * Перенаправляем на Eclipse Temurin с тем же major JDK.
     */
    private static String remapLegacyJvmDockerImageFromModel(String ref, RuntimeLang execLang) {
        if (ref == null || ref.isBlank() || !(execLang == RuntimeLang.JAVA || execLang == RuntimeLang.KOTLIN)) {
            return ref;
        }
        String r = ref.trim();
        String low = r.toLowerCase(Locale.ROOT);
        if (low.equals("openjdk") || low.equals("library/openjdk")) {
            return "eclipse-temurin:21-jdk";
        }
        Matcher dubOpenjdk = Pattern.compile("(?i)^(?:library/)?openjdk:(.+)").matcher(r);
        if (dubOpenjdk.matches()) {
            return temurinJdkFromNumericTag(dubOpenjdk.group(1));
        }
        Matcher adopt = Pattern.compile("(?i)^adoptopenjdk/openjdk:(.+)").matcher(r);
        if (adopt.matches()) {
            return temurinJdkFromNumericTag(adopt.group(1));
        }
        return r;
    }

    private static String temurinJdkFromNumericTag(String tagPart) {
        if (tagPart == null || tagPart.isBlank()) {
            return "eclipse-temurin:21-jdk";
        }
        Matcher ver = Pattern.compile("(\\d{1,2})").matcher(tagPart);
        if (ver.find()) {
            return "eclipse-temurin:" + ver.group(1) + "-jdk";
        }
        return "eclipse-temurin:21-jdk";
    }

    /**
     * Допускаем только «типичный» ref без пробелов и подстановок shell —
     * иначе подставляем образ по умолчанию и не выполняем чужую команду через подставленную строку образа.
     */
    static boolean looksLikeSafeOciImageReference(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        if (ref.length() > 260) {
            return false;
        }
        if (ref.contains("..") || ref.contains("`") || ref.contains("$")) {
            return false;
        }
        // Разрешённые символы в registry/name:tag@digest
        if (!ref.matches("^[a-zA-Z0-9._:@/+-]+$")) {
            return false;
        }
        char first = ref.charAt(0);
        char lastCh = ref.charAt(ref.length() - 1);
        if (!(Character.isLetterOrDigit(first) || first == '_')) {
            return false;
        }
        return Character.isLetterOrDigit(lastCh) || lastCh == '_' || lastCh == '-' || ref.contains(":");
    }

    private static List<String> sanitizeModelTestSteps(List<String> stepsIn) {
        List<String> out = new ArrayList<>();
        if (stepsIn == null) {
            return out;
        }
        int n = 0;
        for (String raw : stepsIn) {
            if (n >= MAX_STEPS_COUNT) {
                break;
            }
            if (raw == null) {
                continue;
            }
            for (String line : raw.replace('\r', '\n').split("\n")) {
                if (n >= MAX_STEPS_COUNT) {
                    break;
                }
                String stripped = stripModelStepDecorations(line);
                stripped = STEP_TRAILING_MANUAL_NOTE.matcher(stripped).replaceFirst("").trim();
                stripped = stripBinaryControlKeepingTextWhitespace(stripped);
                if (stripped.isEmpty()) {
                    continue;
                }
                if (stripped.length() > MAX_STEP_STRING_CHARS) {
                    stripped = stripped.substring(0, MAX_STEP_STRING_CHARS).trim();
                }
                out.add(stripped);
                n++;
            }
        }
        return out;
    }

    private static String stripModelStepDecorations(String line) {
        String ln = line.trim();
        if (ln.startsWith("<") && ln.contains(">")) {
            ln = ln.replaceAll("</?[^>]+>", "").trim();
        }
        ln = ln.replaceFirst("(?u)^[#]{1,6}\\s+", "");
        ln = ln.replaceFirst("(?iu)^[*+•●▪►-]\\s+", "");
        ln = ln.replaceFirst("(?iu)^\\(\\d+\\)\\s*", "");
        ln = ln.replaceFirst("(?iu)^\\d+[.)．]\\s*", "");
        ln = ln.replaceFirst("(?iu)^(шаг|step)\\s*\\d+\\s*[.:\\)\\]\\-—]\\s*", "");
        ln = ln.replaceFirst("(?iu)^(команда|ввести|ввод|введите)\\s*[—:\\-]?\\s*", "");
        ln = ln.replaceFirst("(?iu)^(запуск|launch|run)\\s*[—:]?\\s*", "");
        ln = ln.replaceFirst("(?u)^[\"«»„“”']+", "");
        ln = ln.replaceFirst("(?u)[\"«»„“”']+$", "");
        return ln.trim();
    }

    private static String stripBinaryControlKeepingTextWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\t' && ch != '\n' && ch != '\r' && ch < '\u0020') {
                continue;
            }
            if (ch == '\u007f') {
                continue;
            }
            b.append(ch);
        }
        return b.toString().trim();
    }

    private static String capUtf16(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
                + "\n… [stdin truncated, max " + maxChars + " chars]\n";
    }

    private static String abbrevForLog(String s, int len) {
        if (s == null) {
            return "";
        }
        String x = replaceInvisible(s.strip());
        if (x.length() <= len) {
            return x;
        }
        return x.substring(0, Math.max(0, len - 3)) + "...";
    }

    /** Убирает zero-width символы, которые модели любят «спрятать» в тексте образа или команды. */
    private static String replaceInvisible(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\u200B\\uFEFF]", "");
    }

    /**
     * Лабораторные вроде Pirogi: «2», eggs, flour, shugar, bake, bake без повторных ингредиентов.
     * Если после первого блока идут только bake, добавляем тот же блок перед каждым bake,
     * иначе программа висит на {@code nextLine()}, а второй выпечке не хватает запасов.
     */
    private static List<String> expandPreambleBeforeEachConsecutiveBake(List<String> tokens, boolean parseIntFirst) {
        if (!parseIntFirst || tokens.size() < 4) {
            return tokens;
        }
        String head = tokens.getFirst();
        if (!head.matches("^-?\\d+$")) {
            return tokens;
        }
        List<String> tail = new ArrayList<>(tokens.subList(1, tokens.size()));
        int fb = tail.indexOf("bake");
        if (fb < 0 || fb == 0) {
            return tokens;
        }
        List<String> preamble = new ArrayList<>(tail.subList(0, fb));
        if (preamble.isEmpty()) {
            return tokens;
        }
        List<String> suffix = tail.subList(fb, tail.size());
        if (!suffix.stream().allMatch("bake"::equals)) {
            return tokens;
        }
        int bakes = suffix.size();
        if (bakes <= 1) {
            return tokens;
        }
        if (bakes > 24) {
            return tokens;
        }
        List<String> out = new ArrayList<>(tokens.size() + preamble.size() * (bakes - 1));
        out.add(head);
        for (int i = 0; i < bakes; i++) {
            out.addAll(preamble);
            out.add("bake");
        }
        return out;
    }

    private String concatenateJavaSources(Path workspace) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : listFiles(workspace)) {
            String ps = p.toString().replace('\\', '/');
            if (!ps.endsWith(".java") || ps.contains("/target/")) {
                continue;
            }
            sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    private static final int MAX_EXTRACTED_STDIN_TOKENS = 4_096;
    private static final Pattern INLINE_BACKTICK = Pattern.compile("`([^`]{1,400})`");

    private static List<String> extractInteractiveCommandTokens(List<String> steps) {
        Pattern quoted = Pattern.compile("\"([^\"]{1,200})\"|'([^']{1,200})'");
        List<String> out = new ArrayList<>();
        for (String step : steps) {
            if (step == null || step.isBlank()) {
                continue;
            }
            for (String rawLine : step.split("\\R")) {
                if (rawLine == null || rawLine.isBlank()) {
                    continue;
                }
                String line = stripModelStepDecorations(rawLine);
                if (line.isBlank()) {
                    continue;
                }
                int sizeBeforeFragments = out.size();
                Matcher mb = INLINE_BACKTICK.matcher(line);
                while (mb.find() && out.size() < MAX_EXTRACTED_STDIN_TOKENS) {
                    addStdinTokensFromChunk(mb.group(1), out);
                }
                if (out.size() > sizeBeforeFragments) {
                    continue;
                }
                Matcher mq = quoted.matcher(line);
                while (mq.find() && out.size() < MAX_EXTRACTED_STDIN_TOKENS) {
                    String tok = mq.group(1) != null ? mq.group(1) : mq.group(2);
                    tok = tok == null ? "" : tok.trim().toLowerCase(Locale.ROOT);
                    if (isLabStdinToken(tok)) {
                        out.add(tok);
                    }
                }
                if (out.size() > sizeBeforeFragments) {
                    continue;
                }
                for (String piece : line.split("[,;|]+|\\s+")) {
                    if (out.size() >= MAX_EXTRACTED_STDIN_TOKENS) {
                        break;
                    }
                    String t = piece.trim();
                    if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                        t = t.substring(1, t.length() - 1).trim();
                    }
                    if (t.startsWith("'") && t.endsWith("'") && t.length() >= 2) {
                        t = t.substring(1, t.length() - 1).trim();
                    }
                    String low = t.toLowerCase(Locale.ROOT);
                    if (!low.isEmpty() && isLabStdinToken(low)) {
                        out.add(low);
                    }
                }
            }
        }
        return out;
    }

    private static void addStdinTokensFromChunk(String fragment, List<String> out) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        String f = fragment.trim().replace('\t', '\n').replace('|', '\n').replace(',', '\n').replace(';', '\n');
        for (String p : f.split("\\s+|\\n+")) {
            if (out.size() >= MAX_EXTRACTED_STDIN_TOKENS) {
                break;
            }
            String low = p.trim().toLowerCase(Locale.ROOT);
            if (isLabStdinToken(low)) {
                out.add(low);
            }
        }
    }

    private static boolean isLabStdinToken(String low) {
        if (low == null || low.isEmpty()) {
            return false;
        }
        int len = low.length();
        if (len > 128) {
            return false;
        }
        if (low.matches("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")) {
            return true;
        }
        return low.matches("[a-z][a-z0-9_-]*");
    }

    private List<ExecResult> runLanguagePipeline(Path workspace, RuntimeLang lang, CodeExecProgressSink sink) throws IOException, InterruptedException {
        return switch (lang) {
            case PYTHON -> runPython(workspace, sink);
            case JAVA -> runJava(workspace, sink);
            case KOTLIN -> runKotlin(workspace, sink);
            case CPP -> runCpp(workspace, sink);
            case CSHARP -> runCSharp(workspace, sink);
            case UNKNOWN -> List.of();
        };
    }

    private List<ExecResult> runPython(Path workspace, CodeExecProgressSink sink) throws IOException, InterruptedException {
        List<ExecResult> out = new ArrayList<>();
        boolean hasPytest = listFiles(workspace).stream().anyMatch(p -> p.getFileName().toString().startsWith("test_") || p.getFileName().toString().endsWith("_test.py"));
        if (hasPytest) {
            out.add(runDocker(workspace, "python:3.12", "python", "python -m pytest -q", sink));
        } else {
            out.add(runDocker(workspace, "python:3.12", "python", "python -m py_compile $(find . -name '*.py' | tr '\\n' ' ')", sink));
        }
        return out;
    }

    private List<ExecResult> runJava(Path workspace, CodeExecProgressSink sink) throws IOException, InterruptedException {
        List<ExecResult> out = new ArrayList<>();
        if (Files.exists(workspace.resolve("pom.xml"))) {
            out.add(runDocker(workspace, "maven:3.9-eclipse-temurin-21", "java", "mvn -q test", sink));
            return out;
        }
        if (Files.exists(workspace.resolve("gradlew")) || Files.exists(workspace.resolve("build.gradle")) || Files.exists(workspace.resolve("build.gradle.kts"))) {
            out.add(runDocker(workspace, "gradle:8.10.2-jdk21", "java", "chmod +x ./gradlew || true; ./gradlew test -q", sink));
            return out;
        }
        List<RunnableUnit> units = discoverRunnableUnits(workspace);
        if (!units.isEmpty()) {
            for (RunnableUnit u : units) {
                out.add(runDocker(workspace, u.dockerImage(), u.language(), u.command(), sink));
            }
            return out;
        }
        out.add(runDocker(workspace, "eclipse-temurin:21-jdk", "java", javacCompileAllSourcesToOutDir(), sink));
        return out;
    }

    /**
     * Пустой command у теста модели → подставляется общий языковой прогон.
     * Успешная только компиляция не должна отображаться как PASSED сценария.
     */
    private static String mapEmptyCommandFallbackStatus(ExecResult fb) {
        if (fb == null || !"PASSED".equals(fb.status())) {
            return fb == null ? "SKIPPED" : fb.status();
        }
        String cmd = fb.command() == null ? "" : fb.command().toLowerCase(Locale.ROOT);
        boolean ranProgram = cmd.contains(" java ")
                || cmd.contains("&& java ")
                || cmd.contains("pytest")
                || cmd.contains("mvn ")
                || cmd.contains("gradlew")
                || cmd.contains("dotnet test")
                || cmd.contains("python \"")
                || cmd.contains("ctest")
                || cmd.contains("/tmp/app");
        return ranProgram ? "PASSED" : "FALLBACK";
    }

    private List<ExecResult> runKotlin(Path workspace, CodeExecProgressSink sink) throws IOException, InterruptedException {
        List<ExecResult> out = new ArrayList<>();
        if (Files.exists(workspace.resolve("gradlew")) || Files.exists(workspace.resolve("build.gradle")) || Files.exists(workspace.resolve("build.gradle.kts"))) {
            out.add(runDocker(workspace, "gradle:8.10.2-jdk21", "kotlin", "chmod +x ./gradlew || true; ./gradlew test -q", sink));
            return out;
        }
        out.add(runDocker(workspace, "gradle:8.10.2-jdk21", "kotlin", "kotlinc $(find . -name '*.kt' | tr '\\n' ' ') -d /tmp/out.jar", sink));
        return out;
    }

    private List<ExecResult> runCpp(Path workspace, CodeExecProgressSink sink) throws IOException, InterruptedException {
        List<ExecResult> out = new ArrayList<>();
        if (Files.exists(workspace.resolve("CMakeLists.txt"))) {
            out.add(runDocker(workspace, "gcc:14", "c++", "cmake -S . -B build && cmake --build build", sink));
            out.add(runDocker(workspace, "gcc:14", "c++", "cd build && ctest --output-on-failure || true", sink));
            return out;
        }
        out.add(runDocker(workspace, "gcc:14", "c++", "g++ -std=c++17 $(find . -name '*.cpp' -o -name '*.cc' -o -name '*.cxx' | tr '\\n' ' ') -o /tmp/app", sink));
        return out;
    }

    private List<ExecResult> runCSharp(Path workspace, CodeExecProgressSink sink) throws IOException, InterruptedException {
        List<ExecResult> out = new ArrayList<>();
        boolean hasProject = listFiles(workspace).stream().anyMatch(p -> p.toString().endsWith(".sln") || p.toString().endsWith(".csproj"));
        if (hasProject) {
            out.add(runDocker(workspace, "mcr.microsoft.com/dotnet/sdk:8.0", "c#", "dotnet test -v minimal || dotnet build -v minimal", sink));
            return out;
        }
        out.add(runDocker(workspace, "mcr.microsoft.com/dotnet/sdk:8.0", "c#", "dotnet new console -n tempcheck -o /tmp/tempcheck --force && cp $(find . -name '*.cs' | head -n 1) /tmp/tempcheck/Program.cs && dotnet build /tmp/tempcheck -v minimal", sink));
        return out;
    }

    private ExecResult runDocker(Path workspace, String image, String language, String command, CodeExecProgressSink sink) throws IOException, InterruptedException {
        return runDocker(workspace, image, language, command, "", sink);
    }

    private ExecResult runDocker(Path workspace, String image, String language, String command, String stdinText, CodeExecProgressSink sink) throws IOException, InterruptedException {
        sink.emit("RUNTIME_DOCKER", image + ": " + command);
        try {
            if (runner.enabled()) {
                byte[] zip = zipDirectory(workspace);
                if (zip.length > maxRunnerArchiveBytes) {
                    return new ExecResult(commandName(command), language, command, "FAILED", -1,
                            "Размер архива workspace (" + zip.length + " байт) превышает лимит runner");
                }
                AiCheckDockerRunnerClient.RunnerRunResult r = runner.remoteRun(zip, image, command, dockerCpus, dockerMemory, stdinText);
                if (r == null) {
                    return new ExecResult(commandName(command), language, command, "FAILED", -1, "Пустой ответ runner");
                }
                String st = r.status() == null ? "" : r.status().toUpperCase(Locale.ROOT);
                int code = r.exitCode();
                String output = trimOutput(r.output());
                boolean ok = "PASSED".equals(st) || (st.isEmpty() && code == 0);
                return new ExecResult(commandName(command), language, command, ok ? "PASSED" : ("TIMEOUT".equals(st) ? "TIMEOUT" : "FAILED"),
                        code, output.isEmpty() ? "(no output)" : output);
            }
        } catch (Exception ex) {
            log.warn("Remote runner execution failed ({}): {}", commandName(command), ex.getMessage());
            return new ExecResult(commandName(command), language, command, "FAILED", -1, trimOutput(ex.getMessage()));
        }

        List<String> dockerArgs = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--cpus", dockerCpus,
                "-m", dockerMemory,
                "--network", "none"));
        boolean feedStdin = stdinText != null && !stdinText.isEmpty();
        if (feedStdin) {
            dockerArgs.add("-i");
        }
        dockerArgs.add("-v");
        dockerArgs.add(workspace.toAbsolutePath() + ":/workspace");
        dockerArgs.add("-w");
        dockerArgs.add("/workspace");
        dockerArgs.add(image);
        dockerArgs.add("sh");
        dockerArgs.add("-lc");
        dockerArgs.add(command);
        ProcessBuilder pb = new ProcessBuilder(dockerArgs);
        Process process = pb.start();
        if (feedStdin) {
            process.getOutputStream().write(stdinText.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
        }
        boolean finished = process.waitFor(EXEC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ExecResult(commandName(command), language, command, "TIMEOUT", -1, "Превышен лимит времени выполнения");
        }
        String output = readCombined(process.getInputStream(), process.getErrorStream());
        int code = process.exitValue();
        return new ExecResult(commandName(command), language, command, code == 0 ? "PASSED" : "FAILED", code, trimOutput(output));
    }

    private byte[] zipDirectory(Path root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            List<Path> files = listFiles(root);
            for (Path path : files) {
                Path rel = root.relativize(path);
                String name = rel.toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(name));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private String commandName(String cmd) {
        String c = cmd.trim();
        int idx = c.indexOf(' ');
        return idx > 0 ? c.substring(0, idx) : c;
    }

    private String readCombined(InputStream stdout, InputStream stderr) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stdout.transferTo(out);
        stderr.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String trimOutput(String output) {
        if (output == null) return "";
        String s = output.trim();
        int max = 6000;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...output truncated...";
    }

    private List<Path> listFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    /**
     * Убирает служебные строки из старых сдач (плейсхолдер, блоки {@code --- файл: ... ---})
     * перед записью кода в workspace.
     */
    static String extractCompilableSource(String studentText) {
        if (studentText == null || studentText.isBlank()) {
            return "";
        }
        String t = studentText.trim();
        if (isSubmissionPlaceholder(t)) {
            return "";
        }
        String lower = t.toLowerCase(Locale.ROOT);
        int bestStart = -1;
        for (String marker : List.of(
                "--- файл:",
                "--- file:",
                "--- содержимое вложения ---")) {
            int idx = lower.lastIndexOf(marker);
            if (idx >= 0) {
                int contentStart = t.indexOf('\n', idx);
                if (contentStart >= 0 && contentStart + 1 < t.length()) {
                    bestStart = Math.max(bestStart, contentStart + 1);
                }
            }
        }
        if (bestStart > 0) {
            t = t.substring(bestStart).trim();
        }
        return stripLeadingNonCodeLines(t);
    }

    private static boolean isSubmissionPlaceholder(String text) {
        String n = text.trim();
        return n.equalsIgnoreCase("См. вложенный файл")
                || n.equalsIgnoreCase("См. приложенный файл")
                || n.equalsIgnoreCase("See attached file");
    }

    private static String stripLeadingNonCodeLines(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        int start = 0;
        for (; start < lines.length; start++) {
            String line = lines[start].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("package ")
                    || line.startsWith("import ")
                    || line.startsWith("public class")
                    || line.startsWith("class ")
                    || line.startsWith("```")
                    || line.startsWith("//")
                    || line.startsWith("/*")
                    || line.startsWith("@")
                    || line.startsWith("def ")
                    || line.startsWith("fun ")
                    || line.startsWith("#include")) {
                break;
            }
            if (line.matches("^[\\p{IsAlphabetic}\\p{IsDigit}\\s.,:;!?«»\"'\\-–—]+$")
                    && line.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.CYRILLIC)) {
                continue;
            }
            break;
        }
        if (start == 0) {
            return text.trim();
        }
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length)).trim();
    }

    private static String stripCodeFence(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.startsWith("```")) {
            int firstNl = raw.indexOf('\n');
            int end = raw.lastIndexOf("```");
            if (firstNl >= 0 && end > firstNl) {
                return raw.substring(firstNl + 1, end).trim();
            }
        }
        return raw;
    }

    private String buildSummary(RuntimeLang lang, List<ExecResult> results) {
        long pass = results.stream().filter(r -> "PASSED".equals(r.status())).count();
        long fail = results.stream().filter(r -> "FAILED".equals(r.status())).count();
        long timeout = results.stream().filter(r -> "TIMEOUT".equals(r.status())).count();
        return "Runtime: " + lang.label + " · passed=" + pass + ", failed=" + fail + ", timeout=" + timeout;
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    public record ExecResult(
            String name,
            String language,
            String command,
            String status,
            int exitCode,
            String output
    ) {
    }

    public record ExecutionReport(
            String summary,
            List<ExecResult> results
    ) {
    }

    private enum RuntimeLang {
        PYTHON("python"),
        JAVA("java"),
        KOTLIN("kotlin"),
        CPP("c++"),
        CSHARP("c#"),
        UNKNOWN("unknown");
        private final String label;

        RuntimeLang(String label) {
            this.label = label;
        }
    }
}

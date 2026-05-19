package diplom.platform.aicheck.application;

import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.infrastructure.storage.FileStorageService;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Готовит входные данные для модели в зависимости от типа источника:
 * - TEXT/DOCUMENT — отдаёт текст как есть либо извлекает текст из загруженного файла,
 * - IMAGE — base64 изображения,
 * - CODE_ARCHIVE — распаковка zip и склейка ключевых файлов с лимитами,
 * - CODE_GIT — поверхностное клонирование, затем как archive.
 * Все большие данные обрезаются до безопасных лимитов, чтобы не выйти за токены модели.
 */
@Service
public class AiCheckSourcePreparator {

    private static final Logger log = LoggerFactory.getLogger(AiCheckSourcePreparator.class);

    private static final long MAX_FILE_BYTES = 200_000;
    private static final long MAX_TOTAL_BYTES = 1_500_000;
    private static final int MAX_FILES = 80;
    private static final Set<String> CODE_EXT = Set.of(
            ".java", ".kt", ".scala", ".groovy", ".py", ".rb", ".php", ".js", ".ts", ".tsx", ".jsx",
            ".go", ".rs", ".c", ".h", ".cpp", ".hpp", ".cs", ".swift", ".m", ".mm", ".sql",
            ".html", ".css", ".scss", ".less", ".vue", ".json", ".xml", ".yaml", ".yml", ".toml",
            ".gradle", ".pom", ".dockerfile", ".sh", ".ps1", ".bat", ".md", ".txt"
    );

    private final FileStorageService files;
    private final long maxArchiveSize;
    private final int maxArchiveFiles;
    private final Path workingDir;

    public AiCheckSourcePreparator(
            FileStorageService files,
            @Value("${platform.aicheck.archive.max-size-bytes:50000000}") long maxArchiveSize,
            @Value("${platform.aicheck.archive.max-files:1500}") int maxArchiveFiles,
            @Value("${platform.aicheck.work-dir:./storage/aicheck-work}") String workDir) throws IOException {
        this.files = files;
        this.maxArchiveSize = maxArchiveSize;
        this.maxArchiveFiles = maxArchiveFiles;
        this.workingDir = Path.of(workDir).toAbsolutePath().normalize();
        Files.createDirectories(this.workingDir);
    }

    public Prepared prepare(AiCheckSourceType type, String studentText, List<String> attachmentFileKeys, String gitUrl) throws IOException {
        String firstKey = (attachmentFileKeys == null || attachmentFileKeys.isEmpty()) ? null : attachmentFileKeys.get(0);
        return switch (type) {
            case TEXT -> {
                if (firstKey != null && isReadableCodeAttachment(firstKey)) {
                    String fileText = readAttachmentAsText(firstKey).trim();
                    String st = studentText == null ? "" : studentText.trim();
                    if (st.isEmpty() || st.equals(fileText)) {
                        yield new Prepared(fileText, null, null);
                    } else {
                        yield new Prepared(combine(st, fileText), null, null);
                    }
                }
                yield new Prepared(studentText == null ? "" : studentText, null, null);
            }
            case DOCUMENT -> {
                String docText = readManyTextResources(attachmentFileKeys);
                String combined = combine(studentText, docText);
                yield new Prepared(combined, null, null);
            }
            case IMAGE -> prepareImage(firstKey, studentText);
            case CODE_ARCHIVE -> prepareArchive(firstKey, studentText);
            case CODE_GIT -> prepareGit(gitUrl, studentText);
        };
    }

    private Prepared prepareImage(String fileKey, String studentText) throws IOException {
        if (fileKey == null || fileKey.isBlank()) {
            return new Prepared(studentText == null ? "" : studentText, null, null);
        }
        Resource res = files.load(fileKey);
        try (InputStream in = res.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mime = guessImageMime(res.getFilename());
            String prefix = studentText == null || studentText.isBlank() ? "" : "Подсказка студента: " + studentText.trim() + "\n\n";
            return new Prepared(prefix + "На вход подаётся изображение работы.", base64, mime);
        }
    }

    private Prepared prepareArchive(String fileKey, String studentText) throws IOException {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("Не приложен архив с проектом");
        }
        Resource res = files.load(fileKey);
        try (InputStream in = res.getInputStream()) {
            String content = unzipAndCollect(in, "archive");
            String prefix = studentText == null || studentText.isBlank() ? "" : "Подсказка студента: " + studentText.trim() + "\n\n";
            return new Prepared(prefix + content, null, null);
        }
    }

    private Prepared prepareGit(String gitUrl, String studentText) throws IOException {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Не указан URL репозитория");
        }
        Path tmp = Files.createTempDirectory(workingDir, "git-");
        try {
            Git git = Git.cloneRepository()
                    .setURI(gitUrl.trim())
                    .setDirectory(tmp.toFile())
                    .setDepth(1)
                    .setCloneAllBranches(false)
                    .call();
            git.close();
            String content = collectFromDirectory(tmp);
            String prefix = (studentText == null || studentText.isBlank() ? "" : "Подсказка студента: " + studentText.trim() + "\n\n");
            return new Prepared(prefix + "Репозиторий: " + gitUrl + "\n\n" + content, null, null);
        } catch (Exception e) {
            throw new IOException("Не удалось склонировать репозиторий: " + e.getMessage(), e);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private String unzipAndCollect(InputStream zipIn, String label) throws IOException {
        Path tmp = Files.createTempDirectory(workingDir, label + "-");
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            int fileCount = 0;
            long totalBytes = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (fileCount >= maxArchiveFiles) {
                    break;
                }
                Path out = tmp.resolve(entry.getName()).normalize();
                if (!out.startsWith(tmp)) {
                    throw new IOException("Подозрительный путь в архиве: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    long size = Files.copy(zis, out);
                    totalBytes += size;
                    fileCount++;
                    if (totalBytes > maxArchiveSize) {
                        throw new IOException("Архив превысил лимит " + maxArchiveSize + " байт");
                    }
                }
                zis.closeEntry();
            }
            return collectFromDirectory(tmp);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private String collectFromDirectory(Path root) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Структура проекта:\n");
        List<Path> tree;
        try (Stream<Path> walk = Files.walk(root)) {
            tree = walk.sorted().toList();
        }
        for (Path p : tree) {
            if (Files.isDirectory(p)) {
                continue;
            }
            sb.append("- ").append(root.relativize(p)).append('\n');
        }
        sb.append("\nКлючевые файлы:\n\n");

        long emitted = 0;
        int filesEmitted = 0;
        List<Path> sortedFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.startsWith(".") && !name.equals(".gitignore")) {
                            return false;
                        }
                        int dot = name.lastIndexOf('.');
                        if (dot < 0) {
                            return false;
                        }
                        return CODE_EXT.contains(name.substring(dot));
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(sortedFiles::add);
        }
        for (Path p : sortedFiles) {
            if (filesEmitted >= MAX_FILES || emitted >= MAX_TOTAL_BYTES) {
                break;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(p);
            } catch (IOException ex) {
                continue;
            }
            if (bytes.length > MAX_FILE_BYTES) {
                bytes = java.util.Arrays.copyOf(bytes, (int) MAX_FILE_BYTES);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            sb.append("===== ").append(root.relativize(p)).append(" =====\n");
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
            emitted += bytes.length;
            filesEmitted++;
        }
        return sb.toString();
    }

    public String readAttachmentAsText(String fileKey) {
        return readTextResource(fileKey);
    }

    private String readTextResource(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return "";
        }
        try {
            Resource res = files.load(fileKey);
            try (InputStream in = res.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length > MAX_FILE_BYTES * 4L) {
                    bytes = java.util.Arrays.copyOf(bytes, (int) (MAX_FILE_BYTES * 4L));
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Не удалось прочитать вложение {}: {}", fileKey, e.getMessage());
            return "";
        }
    }

    private String readManyTextResources(List<String> fileKeys) {
        if (fileKeys == null || fileKeys.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (String key : fileKeys) {
            String content = readTextResource(key);
            if (content.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("--- вложение #").append(idx++).append(" (").append(key).append(") ---\n");
            sb.append(content);
        }
        return sb.toString();
    }

    private static boolean isReadableCodeAttachment(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".jsx") || lower.endsWith(".c") || lower.endsWith(".cpp")
                || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".cs")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".scala")
                || lower.endsWith(".rb") || lower.endsWith(".php") || lower.endsWith(".sql")
                || lower.endsWith(".sh") || lower.endsWith(".swift") || lower.endsWith(".m");
    }

    private static String combine(String text, String additional) {
        if (text == null || text.isBlank()) {
            return additional == null ? "" : additional;
        }
        if (additional == null || additional.isBlank()) {
            return text;
        }
        return text + "\n\n--- содержимое вложения ---\n\n" + additional;
    }

    private static String guessImageMime(String filename) {
        if (filename == null) {
            return "image/png";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public record Prepared(String preparedContent, String imageBase64, String imageMimeType) {
    }
}

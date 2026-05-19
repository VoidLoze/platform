package diplom.platform.ui;

import diplom.platform.application.CourseService;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.infrastructure.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileStorageService files;
    private final CourseService courseService;

    public FileController(FileStorageService files, CourseService courseService) {
        this.files = files;
        this.courseService = courseService;
    }

    @PostMapping("/upload")
    public UploadedFileDto upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "generic") String scope) {
        String key = files.save(file, scope);
        log.info("Stored upload scope={} key={} name={} bytes={}",
                scope, key, file.getOriginalFilename(), file.getSize());
        return new UploadedFileDto(key, file.getOriginalFilename(), file.getContentType());
    }

    /**
     * Надёжная выдача файла: один query-параметр с полным ключом (со слешами), без путей вида
     * {@code /files/chat/uuid.jpg}, которые у прокси и {@code Path.resolve} легко ломаются.
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadByQuery(
            @RequestParam("key") String key,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) {
        authorizeFileAccess(key);
        return buildFileResponse(files.load(key), inline);
    }

    @GetMapping("/{*key}")
    public ResponseEntity<Resource> download(
            @PathVariable String key,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) {
        authorizeFileAccess(key);
        return buildFileResponse(files.load(key), inline);
    }

    private void authorizeFileAccess(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("File not found");
        }
        String normalized = key.trim().replace('\\', '/');
        PlatformUser user = SecurityUtils.requireCurrentUser();
        if (normalized.startsWith("courses/")) {
            String[] parts = normalized.split("/");
            if (parts.length >= 2) {
                try {
                    java.util.UUID courseId = java.util.UUID.fromString(parts[1]);
                    courseService.assertCanAccessCourse(courseId, user);
                    return;
                } catch (IllegalArgumentException ignored) {
                    // fall-through
                }
            }
        }
        if (normalized.startsWith("avatar/")
                || normalized.startsWith("student-answers/")
                || normalized.startsWith("ai-check/")
                || normalized.startsWith("chat/")) {
            return;
        }
        if (user.role() != diplom.platform.identity.domain.UserRole.ROLE_ADMIN
                && user.role() != diplom.platform.identity.domain.UserRole.ROLE_TEACHER) {
            throw new org.springframework.security.access.AccessDeniedException("Недостаточно прав к файлу");
        }
    }

    private static ResponseEntity<Resource> buildFileResponse(Resource resource, boolean inline) {
        String filename = resource.getFilename() == null ? "file" : resource.getFilename();
        MediaType mediaType = resolveMediaType(filename);
        String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + filename.replace("\"", "") + "\"";
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(resource);
    }

    private static MediaType resolveMediaType(String filename) {
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".java")) {
            return MediaType.parseMediaType("text/plain;charset=UTF-8");
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".sql")) {
            return MediaType.parseMediaType("text/plain;charset=UTF-8");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public record UploadedFileDto(String key, String originalName, String contentType) {}
}

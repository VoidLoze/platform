package diplom.platform.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.tools.GenerateRegistrationSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * При старте: пустые {@code platform.registration.*} дополняются из файла в {@code platform.files.base-dir}
 * или генерируются и сохраняются атомарно. Повторный запуск читает тот же файл (Docker volume).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistrationSecretsRuntime implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(RegistrationSecretsRuntime.class);
    public static final String INVITE_FILE_NAME = ".registration-invite-secrets.json";

    private final RegistrationProperties props;
    private final ApplicationArguments applicationArguments;
    private final String filesBaseDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean bootstrapped = new AtomicBoolean(false);
    private final LinkedHashSet<String> teacherCodes = new LinkedHashSet<>();
    private final LinkedHashSet<String> adminCodes = new LinkedHashSet<>();
    private volatile String resolutionSource = "ENV";

    public RegistrationSecretsRuntime(
            RegistrationProperties props,
            ApplicationArguments applicationArguments,
            @Value("${platform.files.base-dir:./storage}") String filesBaseDir
    ) {
        this.props = props;
        this.applicationArguments = applicationArguments;
        this.filesBaseDir = filesBaseDir;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        if (applicationArguments.containsOption("generate-registration-secrets")) {
            return;
        }
        if (!bootstrapped.compareAndSet(false, true)) {
            return;
        }
        try {
            bootstrap();
        } catch (Exception ex) {
            log.error("Не удалось инициализировать коды приглашения для регистрации staff", ex);
            teacherCodes.clear();
            adminCodes.clear();
            if (!trimToEmpty(props.getTeacherSecret()).isBlank()) {
                teacherCodes.add(trimToEmpty(props.getTeacherSecret()));
            }
            if (!trimToEmpty(props.getAdminSecret()).isBlank()) {
                adminCodes.add(trimToEmpty(props.getAdminSecret()));
            }
            resolutionSource = "ENV";
        }
    }

    private void bootstrap() throws IOException {
        String envT = trimToEmpty(props.getTeacherSecret()); // backward-compatible seed
        String envA = trimToEmpty(props.getAdminSecret());   // backward-compatible seed
        Path base = Path.of(filesBaseDir).toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path secretFile = base.resolve(INVITE_FILE_NAME);

        StoredFile stored = readStored(secretFile);
        teacherCodes.clear();
        adminCodes.clear();

        if (stored != null) {
            teacherCodes.addAll(stored.teacherCodes);
            adminCodes.addAll(stored.adminCodes);
        }

        if (!envT.isEmpty()) {
            teacherCodes.add(envT);
        }
        if (!envA.isEmpty()) {
            adminCodes.add(envA);
        }

        if (teacherCodes.isEmpty()) {
            teacherCodes.add(GenerateRegistrationSecrets.randomSecret());
        }
        if (adminCodes.isEmpty()) {
            adminCodes.add(GenerateRegistrationSecrets.randomSecret());
        }

        boolean firstFile = !Files.isRegularFile(secretFile);
        writeStored(secretFile, teacherCodes, adminCodes);
        if (firstFile) {
            log.info("Создан файл с одноразовыми кодами приглашения staff: {}", secretFile);
        }

        resolutionSource = (!envT.isEmpty() || !envA.isEmpty()) ? "MIXED" : "FILE";
        props.setTeacherSecret("");
        props.setAdminSecret("");
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public synchronized List<String> teacherCodes() {
        return List.copyOf(teacherCodes);
    }

    public synchronized List<String> adminCodes() {
        return List.copyOf(adminCodes);
    }

    public String getResolutionSource() {
        return resolutionSource;
    }

    public Path getInviteFilePath() {
        Path base = Path.of(filesBaseDir).toAbsolutePath().normalize();
        return base.resolve(INVITE_FILE_NAME);
    }

    public synchronized List<String> generate(String scope, int count) throws IOException {
        int n = Math.max(1, Math.min(count, 500));
        List<String> generated = new ArrayList<>();
        if ("teacher".equalsIgnoreCase(scope) || "both".equalsIgnoreCase(scope)) {
            for (int i = 0; i < n; i++) {
                String c = GenerateRegistrationSecrets.randomSecret();
                teacherCodes.add(c);
                generated.add(c);
            }
        }
        if ("admin".equalsIgnoreCase(scope) || "both".equalsIgnoreCase(scope)) {
            for (int i = 0; i < n; i++) {
                String c = GenerateRegistrationSecrets.randomSecret();
                adminCodes.add(c);
                generated.add(c);
            }
        }
        writeStored(getInviteFilePath(), teacherCodes, adminCodes);
        resolutionSource = "FILE";
        return generated;
    }

    public synchronized boolean consumeCode(String scope, String providedRaw) throws IOException {
        String provided = trimToEmpty(providedRaw);
        if (provided.isEmpty()) {
            return false;
        }
        boolean removed;
        if ("teacher".equalsIgnoreCase(scope)) {
            removed = teacherCodes.remove(provided);
        } else if ("admin".equalsIgnoreCase(scope)) {
            removed = adminCodes.remove(provided);
        } else {
            return false;
        }
        if (removed) {
            writeStored(getInviteFilePath(), teacherCodes, adminCodes);
            resolutionSource = "FILE";
        }
        return removed;
    }

    public void printExport(java.io.PrintStream out) {
        out.println("\n# Одноразовые коды (первые доступные)");
        out.println("teacher=" + teacherCodes().stream().findFirst().orElse(""));
        out.println("admin=" + adminCodes().stream().findFirst().orElse(""));
        out.println();
    }

    private StoredFile readStored(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            InviteFileDto dto = objectMapper.readValue(file.toFile(), InviteFileDto.class);
            if (dto == null) {
                return null;
            }
            List<String> teacher = dto.teacherCodes == null ? List.of() : dto.teacherCodes.stream().map(RegistrationSecretsRuntime::trimToEmpty).filter(s -> !s.isBlank()).toList();
            List<String> admin = dto.adminCodes == null ? List.of() : dto.adminCodes.stream().map(RegistrationSecretsRuntime::trimToEmpty).filter(s -> !s.isBlank()).toList();
            if (teacher.isEmpty() && dto.teacher != null && !dto.teacher.isBlank()) {
                teacher = List.of(dto.teacher.trim());
            }
            if (admin.isEmpty() && dto.admin != null && !dto.admin.isBlank()) {
                admin = List.of(dto.admin.trim());
            }
            return new StoredFile(teacher, admin, trimToEmpty(dto.createdAt));
        } catch (Exception e) {
            log.warn("Не удалось прочитать {}, будут сгенерированы новые ключи", file, e);
            return null;
        }
    }

    private void writeStored(Path file, LinkedHashSet<String> teacher, LinkedHashSet<String> admin) throws IOException {
        InviteFileDto dto = new InviteFileDto(new ArrayList<>(teacher), new ArrayList<>(admin), Instant.now().toString());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(dto);
        Files.write(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Map<String, Object> adminView() {
        Path p = getInviteFilePath();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", resolutionSource);
        m.put("teacherCodes", teacherCodes());
        m.put("adminCodes", adminCodes());
        m.put("teacherCount", teacherCodes.size());
        m.put("adminCount", adminCodes.size());
        m.put("persistedFile", INVITE_FILE_NAME);
        m.put("persistedAbsolutePath", p.toString());
        return m;
    }

    private static final class StoredFile {
        final List<String> teacherCodes;
        final List<String> adminCodes;
        final String createdAt;

        StoredFile(List<String> teacherCodes, List<String> adminCodes, String createdAt) {
            this.teacherCodes = teacherCodes;
            this.adminCodes = adminCodes;
            this.createdAt = createdAt;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class InviteFileDto {
        public List<String> teacherCodes;
        public List<String> adminCodes;
        public String teacher;
        public String admin;
        public String createdAt;

        @SuppressWarnings("unused")
        InviteFileDto() {
        }

        InviteFileDto(List<String> teacherCodes, List<String> adminCodes, String createdAt) {
            this.teacherCodes = teacherCodes;
            this.adminCodes = adminCodes;
            this.createdAt = createdAt;
        }
    }
}

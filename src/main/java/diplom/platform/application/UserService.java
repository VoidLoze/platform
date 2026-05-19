package diplom.platform.application;

import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.config.RegistrationSecretsRuntime;
import diplom.platform.infrastructure.storage.FileStorageService;
import diplom.platform.ui.dto.UpdateProfileRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {
    private final UserJpaRepository users;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorage;
    private final RegistrationSecretsRuntime registrationSecrets;

    public UserService(
            UserJpaRepository users,
            PasswordEncoder passwordEncoder,
            FileStorageService fileStorage,
            RegistrationSecretsRuntime registrationSecrets
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.fileStorage = fileStorage;
        this.registrationSecrets = registrationSecrets;
    }

    public UserEntity registerUser(
            String firstName,
            String lastName,
            String middleName,
            String email,
            String password,
            UserRole role,
            String staffRegistrationSecret
    ) {
        validatePrivilegedRegistration(role, staffRegistrationSecret);
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Этот email уже зарегистрирован");
        }
        UserEntity user = new UserEntity();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setMiddleName(middleName);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        return users.save(user);
    }

    /**
     * Регистрация студента и привилегированных ролей с проверкой секретов (env и/или автогенерация при старте).
     */
    private void validatePrivilegedRegistration(UserRole role, String providedRaw) {
        if (role == UserRole.ROLE_STUDENT) {
            return;
        }
        try {
            if (role == UserRole.ROLE_TEACHER) {
                if (!registrationSecrets.consumeCode("teacher", providedRaw)) {
                    throw new IllegalArgumentException("Неверный или уже использованный код приглашения для роли преподавателя.");
                }
                return;
            }
            if (role == UserRole.ROLE_ADMIN) {
                if (!registrationSecrets.consumeCode("admin", providedRaw)) {
                    throw new IllegalArgumentException("Неверный или уже использованный код приглашения для роли администратора.");
                }
                return;
            }
            throw new IllegalArgumentException("Неизвестная роль");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Не удалось проверить код приглашения.", e);
        }
    }

    public UserEntity findUserByEmail(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public UserEntity findById(UUID id) {
        return users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public List<UserEntity> listUsers() {
        return users.findAll();
    }

    /**
     * Search users by first/last/full name. Optional role filter applied in-memory after fetch.
     */
    public List<UserEntity> searchUsers(String q, UUID excludeId, int limit, UserRole roleFilter) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 50));
        List<UserEntity> found = users.searchByName(q.trim(), excludeId, page);
        if (roleFilter == null) {
            return found;
        }
        return found.stream().filter(u -> u.getRole() == roleFilter).toList();
    }

    public UserEntity updateProfile(UUID id, UpdateProfileRequest request) {
        UserEntity u = findById(id);
        if (request.firstName() != null && !request.firstName().isBlank()) {
            u.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null && !request.lastName().isBlank()) {
            u.setLastName(request.lastName().trim());
        }
        if (request.middleName() != null) {
            String m = request.middleName().trim();
            u.setMiddleName(m.isEmpty() ? null : m);
        }
        if (request.bio() != null) {
            String b = request.bio().trim();
            u.setBio(b.isEmpty() ? null : b);
        }
        return users.save(u);
    }

    public UserEntity updateAvatar(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File required");
        }
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) {
            throw new IllegalArgumentException("Only image uploads are allowed");
        }
        String key = fileStorage.save(file, "avatar");
        UserEntity u = findById(userId);
        u.setAvatarFileKey(key);
        return users.save(u);
    }
}

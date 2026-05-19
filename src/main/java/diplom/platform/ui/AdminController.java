package diplom.platform.ui;

import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.config.RegistrationSecretsRuntime;
import diplom.platform.ui.dto.GenerateRegistrationInvitesRequest;
import diplom.platform.ui.dto.UserProfileDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final UserJpaRepository users;
    private final RegistrationSecretsRuntime registrationSecrets;

    public AdminController(UserJpaRepository users, RegistrationSecretsRuntime registrationSecrets) {
        this.users = users;
        this.registrationSecrets = registrationSecrets;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserProfileDto> listUsers() {
        return users.findAll().stream().map(AdminController::toDto).toList();
    }

    @GetMapping("/registration-invites")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> registrationInvites() {
        return registrationSecrets.adminView();
    }

    @PostMapping("/registration-invites/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> generateRegistrationInvites(@RequestBody GenerateRegistrationInvitesRequest body)
            throws IOException {
        String scope = body == null || body.scope() == null ? "teacher" : body.scope().trim().toLowerCase(Locale.ROOT);
        int count = body == null ? 1 : body.count();
        if (!scope.equals("teacher") && !scope.equals("admin") && !scope.equals("both")) {
            throw new IllegalArgumentException("scope: ожидается teacher, admin или both");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count должен быть больше 0");
        }
        registrationSecrets.generate(scope, count);
        return registrationSecrets.adminView();
    }

    private static UserProfileDto toDto(UserEntity u) {
        return new UserProfileDto(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                u.getMiddleName(),
                u.getEmail(),
                u.getRole(),
                u.getAvatarFileKey(),
                u.getBio());
    }
}

package diplom.platform.ui;

import diplom.platform.application.UserService;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.infrastructure.storage.FileStorageService;
import diplom.platform.ui.dto.UpdateProfileRequest;
import diplom.platform.ui.dto.UserProfileDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;
    private final FileStorageService files;

    public UserController(UserService userService, FileStorageService files) {
        this.userService = userService;
        this.files = files;
    }

    @PostMapping("/register")
    public UserProfileDto registerUser(@RequestBody RegisterUserRequest request) {
        UserEntity u = userService.registerUser(
                request.firstName(),
                request.lastName(),
                request.middleName(),
                request.email(),
                request.password(),
                request.role(),
                request.staffRegistrationSecret()
        );
        return toDto(u);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileDto me() {
        return toDto(userService.findById(SecurityUtils.requireCurrentUser().id()));
    }

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileDto patchMe(@Valid @RequestBody UpdateProfileRequest request) {
        return toDto(userService.updateProfile(SecurityUtils.requireCurrentUser().id(), request));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public UserProfileDto uploadAvatar(@RequestParam("file") MultipartFile file) {
        try {
            return toDto(userService.updateAvatar(SecurityUtils.requireCurrentUser().id(), file));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Current user's avatar as a single URL (avoids path decoding issues for keys like {@code avatar/uuid.png} behind proxies).
     */
    @GetMapping("/me/avatar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadMyAvatar() {
        UserEntity u = userService.findById(SecurityUtils.requireCurrentUser().id());
        String key = u.getAvatarFileKey();
        if (key == null || key.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = files.load(key);
        String filename = resource.getFilename() != null ? resource.getFilename() : "avatar";
        MediaType contentType = mediaTypeForAvatarFilename(filename);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename.replace("\"", "").replace("\r", "").replace("\n", "") + "\"")
                .body(resource);
    }

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public List<UserProfileDto> searchUsers(
            @RequestParam String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        UUID me = SecurityUtils.requireCurrentUser().id();
        return userService.searchUsers(q.trim(), me, Math.min(Math.max(limit, 1), 50), role).stream()
                .map(UserController::toDto)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userController.isSelf(#id)")
    public UserProfileDto getUserProfile(@PathVariable UUID id) {
        return toDto(userService.findById(id));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<UserProfileDto> listUsers() {
        UUID me = SecurityUtils.requireCurrentUser().id();
        return userService.listUsers().stream()
                .filter(u -> !u.getId().equals(me))
                .map(UserController::toDto)
                .toList();
    }

    @SuppressWarnings("unused")
    public boolean isSelf(UUID id) {
        return SecurityUtils.requireCurrentUser().id().equals(id);
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

    private static MediaType mediaTypeForAvatarFilename(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
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
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterUserRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String middleName,
            @Email String email,
            @NotBlank String password,
            UserRole role,
            /** Секрет из platform.registration.* для ROLE_TEACHER / ROLE_ADMIN */
            String staffRegistrationSecret
    ) {
        public RegisterUserRequest {
            staffRegistrationSecret = staffRegistrationSecret == null ? "" : staffRegistrationSecret;
        }
    }
}

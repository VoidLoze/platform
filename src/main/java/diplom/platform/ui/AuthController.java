package diplom.platform.ui;

import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.security.JwtService;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.ui.dto.AuthTokensDto;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserJpaRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthController(UserJpaRepository users, PasswordEncoder encoder, JwtService jwtService) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public AuthTokensDto login(@RequestBody LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        UserEntity user = users.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return tokensFor(user);
    }

    @PostMapping("/refresh")
    public AuthTokensDto refresh(@RequestBody RefreshRequest request) {
        Claims claims = jwtService.parseAndValidate(request.refreshToken(), "refresh");
        PlatformUser pu = jwtService.toPlatformUser(claims);
        UserEntity user = users.findById(pu.id()).orElseThrow(() -> new IllegalArgumentException("User not found"));
        return tokensFor(user);
    }

    private AuthTokensDto tokensFor(UserEntity user) {
        String access = jwtService.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refresh = jwtService.createRefreshToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthTokensDto(access, refresh, "Bearer", user.getId(), user.getEmail(), user.getRole());
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }
}

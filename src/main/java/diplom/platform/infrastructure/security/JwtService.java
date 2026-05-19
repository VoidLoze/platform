package diplom.platform.infrastructure.security;

import diplom.platform.identity.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(UUID userId, String email, UserRole role) {
        return buildToken(userId, email, role, "access", properties.getAccessTokenValidityMs());
    }

    public String createRefreshToken(UUID userId, String email, UserRole role) {
        return buildToken(userId, email, role, "refresh", properties.getRefreshTokenValidityMs());
    }

    private String buildToken(UUID userId, String email, UserRole role, String typ, long validityMs) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + validityMs);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .claim("typ", typ)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    public Claims parseAndValidate(String token, String expectedTyp) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!expectedTyp.equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("Invalid token type");
        }
        return claims;
    }

    public PlatformUser toPlatformUser(Claims claims) {
        UUID id = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        UserRole role = UserRole.valueOf(claims.get("role", String.class));
        return new PlatformUser(id, email, role);
    }
}

package diplom.platform.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.jwt")
public class JwtProperties {
    /**
     * HS256 secret, min 256 bits recommended for production.
     */
    private String secret = "platform-dev-secret-change-in-production-min-32-chars!!";
    private long accessTokenValidityMs = 900_000L;
    private long refreshTokenValidityMs = 7 * 24 * 60 * 60 * 1000L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenValidityMs() {
        return accessTokenValidityMs;
    }

    public void setAccessTokenValidityMs(long accessTokenValidityMs) {
        this.accessTokenValidityMs = accessTokenValidityMs;
    }

    public long getRefreshTokenValidityMs() {
        return refreshTokenValidityMs;
    }

    public void setRefreshTokenValidityMs(long refreshTokenValidityMs) {
        this.refreshTokenValidityMs = refreshTokenValidityMs;
    }
}

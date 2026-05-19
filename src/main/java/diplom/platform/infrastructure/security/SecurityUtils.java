package diplom.platform.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {
    }

    public static PlatformUser requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof PlatformUser user)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        return user;
    }
}

package diplom.platform.infrastructure.security;

import diplom.platform.identity.domain.UserRole;

import java.util.UUID;

public record PlatformUser(UUID id, String email, UserRole role) {
}

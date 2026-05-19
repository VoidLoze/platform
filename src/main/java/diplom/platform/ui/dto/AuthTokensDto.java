package diplom.platform.ui.dto;

import diplom.platform.identity.domain.UserRole;

import java.util.UUID;

public record AuthTokensDto(String accessToken, String refreshToken, String tokenType, UUID userId, String email, UserRole role) {
}

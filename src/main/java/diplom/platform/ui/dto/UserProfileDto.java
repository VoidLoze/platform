package diplom.platform.ui.dto;

import diplom.platform.identity.domain.UserRole;

import java.util.UUID;

public record UserProfileDto(
        UUID id,
        String firstName,
        String lastName,
        String middleName,
        String email,
        UserRole role,
        String avatarFileKey,
        String bio
) {
}

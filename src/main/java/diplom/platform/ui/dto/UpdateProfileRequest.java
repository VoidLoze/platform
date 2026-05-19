package diplom.platform.ui.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 120) String firstName,
        @Size(max = 120) String lastName,
        @Size(max = 120) String middleName,
        @Size(max = 2000) String bio
) {
}

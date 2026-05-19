package diplom.platform.ui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateRegistrationInvitesRequest(String scope, int count) {
}

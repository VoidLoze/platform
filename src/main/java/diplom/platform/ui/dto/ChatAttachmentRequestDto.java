package diplom.platform.ui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatAttachmentRequestDto(String fileKey, String originalName, String contentType) {
}

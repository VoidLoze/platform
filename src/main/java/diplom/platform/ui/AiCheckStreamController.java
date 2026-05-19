package diplom.platform.ui;

import diplom.platform.aicheck.application.AiCheckProgressSseService;
import diplom.platform.aicheck.application.AiCheckService;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.AiCheckDtos;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai-check")
public class AiCheckStreamController {

    private final AiCheckService aiCheckService;
    private final AiCheckProgressSseService progressSse;

    public AiCheckStreamController(AiCheckService aiCheckService, AiCheckProgressSseService progressSse) {
        this.aiCheckService = aiCheckService;
        this.progressSse = progressSse;
    }

    @GetMapping("/jobs/{jobId}/events")
    @PreAuthorize("isAuthenticated()")
    public List<AiCheckDtos.AiCheckProgressEventDto> listEvents(@PathVariable UUID jobId) {
        aiCheckService.ensureJobAccessible(jobId, SecurityUtils.requireCurrentUser());
        return aiCheckService.listProgressEvents(jobId);
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter subscribe(@PathVariable UUID jobId, @RequestParam(defaultValue = "0") long afterId) {
        aiCheckService.ensureJobAccessible(jobId, SecurityUtils.requireCurrentUser());
        return progressSse.open(jobId, afterId);
    }
}

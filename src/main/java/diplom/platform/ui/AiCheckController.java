package diplom.platform.ui;

import diplom.platform.aicheck.application.AiCheckPolicyService;
import diplom.platform.aicheck.application.AiCheckService;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.AiCheckDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai-check")
public class AiCheckController {

    private final AiCheckService aiCheckService;
    private final AiCheckPolicyService aiCheckPolicyService;

    public AiCheckController(AiCheckService aiCheckService, AiCheckPolicyService aiCheckPolicyService) {
        this.aiCheckService = aiCheckService;
        this.aiCheckPolicyService = aiCheckPolicyService;
    }

    @PostMapping("/jobs")
    @PreAuthorize("isAuthenticated()")
    public AiCheckDtos.AiCheckJobDto createJob(@RequestBody AiCheckDtos.AiCheckCreateRequest req) {
        return aiCheckService.createJob(SecurityUtils.requireCurrentUser(), req);
    }

    @GetMapping("/jobs/mine")
    @PreAuthorize("isAuthenticated()")
    public List<AiCheckDtos.AiCheckJobDto> myJobs() {
        return aiCheckService.listMyJobs(SecurityUtils.requireCurrentUser());
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public AiCheckDtos.AiCheckJobDto getJob(@PathVariable UUID jobId) {
        return aiCheckService.getJob(jobId, SecurityUtils.requireCurrentUser());
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public AiCheckDtos.AiCheckSettingsDto settings() {
        var s = aiCheckPolicyService.getOrCreate();
        return new AiCheckDtos.AiCheckSettingsDto(
                s.getDefaultProvider(),
                java.util.Arrays.stream(s.getFallbackChain().split(",")).map(String::trim).filter(x -> !x.isBlank()).toList(),
                aiCheckPolicyService.subjectPolicy()
        );
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public AiCheckDtos.AiCheckSettingsDto updateSettings(@RequestBody AiCheckDtos.AiCheckSettingsDto dto) {
        var s = aiCheckPolicyService.update(dto.defaultProvider(), dto.fallbackChain(), dto.subjectPolicy());
        return new AiCheckDtos.AiCheckSettingsDto(
                s.getDefaultProvider(),
                java.util.Arrays.stream(s.getFallbackChain().split(",")).map(String::trim).filter(x -> !x.isBlank()).toList(),
                aiCheckPolicyService.subjectPolicy()
        );
    }
}

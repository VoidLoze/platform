package diplom.platform.ui;

import diplom.platform.aicheck.application.AiCheckService;
import diplom.platform.application.LabWorkQueryService;
import diplom.platform.application.LabWorkTestService;
import diplom.platform.ui.dto.AiCheckDtos;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.LabWorkDetailDto;
import diplom.platform.ui.dto.LabWorkSummaryDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/labworks")
public class LabWorkController {
    private final LabWorkQueryService labWorkQueryService;
    private final LabWorkTestService labWorkTestService;
    private final AiCheckService aiCheckService;

    public LabWorkController(LabWorkQueryService labWorkQueryService, LabWorkTestService labWorkTestService, AiCheckService aiCheckService) {
        this.labWorkQueryService = labWorkQueryService;
        this.labWorkTestService = labWorkTestService;
        this.aiCheckService = aiCheckService;
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('STUDENT')")
    public List<LabWorkSummaryDto> mySubmissions() {
        PlatformUser user = SecurityUtils.requireCurrentUser();
        return labWorkQueryService.listMine(user);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public LabWorkDetailDto getOne(@PathVariable UUID id) {
        return labWorkQueryService.getDetail(id, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('STUDENT')")
    public LabWorkTestService.TestSubmitResult submitTest(@PathVariable UUID id, @RequestBody Map<UUID, String> answers) {
        return labWorkTestService.submitTest(id, SecurityUtils.requireCurrentUser(), answers);
    }

    /** ИИ-проверка всей сдачи: текст, файл или оба — что приложено студентом. */
    @PostMapping("/{id}/ai-review")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public AiCheckDtos.AiCheckJobDto reviewWithAi(@PathVariable UUID id) {
        return aiCheckService.createJobFromLabWork(id, SecurityUtils.requireCurrentUser());
    }

    @GetMapping("/{id}/ai-review/latest")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<AiCheckDtos.AiCheckJobDto> latestAiReview(@PathVariable UUID id) {
        AiCheckDtos.AiCheckJobDto dto = aiCheckService.getLatestJobForLabWork(id, SecurityUtils.requireCurrentUser());
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }
}

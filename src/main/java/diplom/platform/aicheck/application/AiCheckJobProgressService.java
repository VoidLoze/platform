package diplom.platform.aicheck.application;

import diplom.platform.aicheck.infrastructure.AiCheckJobEntity;
import diplom.platform.aicheck.infrastructure.AiCheckJobEventEntity;
import diplom.platform.aicheck.infrastructure.AiCheckJobEventJpaRepository;
import diplom.platform.aicheck.infrastructure.AiCheckJobJpaRepository;
import diplom.platform.ui.dto.AiCheckDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AiCheckJobProgressService {

    private final AiCheckJobEventJpaRepository events;
    private final AiCheckJobJpaRepository jobs;

    public AiCheckJobProgressService(AiCheckJobEventJpaRepository events, AiCheckJobJpaRepository jobs) {
        this.events = events;
        this.jobs = jobs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(UUID jobId, String phase, String detail) {
        emit(jobId, phase, detail, "PROJECT", null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(UUID jobId, String phase, String detail, String scope, String filePath, String testId) {
        AiCheckJobEntity job = jobs.getReferenceById(jobId);
        AiCheckJobEventEntity e = new AiCheckJobEventEntity();
        e.setJob(job);
        e.setPhase(phase);
        e.setDetail(detail);
        e.setScope(scope == null || scope.isBlank() ? "PROJECT" : scope);
        e.setFilePath(filePath);
        e.setTestId(testId);
        events.save(e);
    }

    @Transactional(readOnly = true)
    public List<AiCheckDtos.AiCheckProgressEventDto> listEvents(UUID jobId) {
        return events.findByJob_IdOrderByIdAsc(jobId).stream().map(e -> new AiCheckDtos.AiCheckProgressEventDto(
                e.getId(),
                e.getPhase(),
                e.getDetail(),
                e.getScope(),
                e.getFilePath(),
                e.getTestId(),
                e.getCreatedAt()
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<AiCheckDtos.AiCheckProgressEventDto> eventsAfter(UUID jobId, long afterId) {
        return events.findByJob_IdAndIdGreaterThanOrderByIdAsc(jobId, afterId).stream().map(e -> new AiCheckDtos.AiCheckProgressEventDto(
                e.getId(),
                e.getPhase(),
                e.getDetail(),
                e.getScope(),
                e.getFilePath(),
                e.getTestId(),
                e.getCreatedAt()
        )).toList();
    }
}

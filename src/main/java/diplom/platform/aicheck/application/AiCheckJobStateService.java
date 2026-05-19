package diplom.platform.aicheck.application;

import diplom.platform.aicheck.infrastructure.AiCheckJobEntity;
import diplom.platform.aicheck.infrastructure.AiCheckJobJpaRepository;
import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.evaluation.infrastructure.LabWorkJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Короткие транзакции смены статуса задания: долгая AI-проверка не должна держать {@code RUNNING}
 * незафиксированным — иначе клиент вечно видит {@code QUEUED}.
 */
@Service
public class AiCheckJobStateService {

    private final AiCheckJobJpaRepository jobs;
    private final LabWorkJpaRepository labWorks;

    public AiCheckJobStateService(AiCheckJobJpaRepository jobs, LabWorkJpaRepository labWorks) {
        this.jobs = jobs;
        this.labWorks = labWorks;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRunning(UUID id) {
        return jobs.findById(id)
                .map(job -> {
                    if (job.getStatus() != AiCheckJobEntity.Status.QUEUED) {
                        return false;
                    }
                    job.setStatus(AiCheckJobEntity.Status.RUNNING);
                    job.setStartedAt(OffsetDateTime.now());
                    jobs.save(job);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String message) {
        jobs.findById(id).ifPresent(job -> {
            job.setStatus(AiCheckJobEntity.Status.FAILED);
            job.setErrorMessage(message);
            job.setFinishedAt(OffsetDateTime.now());
            if (job.getLabWork() != null) {
                job.getLabWork().setStatus(LabWorkStatus.FAILED);
                labWorks.save(job.getLabWork());
            }
            jobs.save(job);
        });
    }
}

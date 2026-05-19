package diplom.platform.aicheck.application;

import diplom.platform.aicheck.infrastructure.AiCheckJobEntity;
import diplom.platform.aicheck.infrastructure.AiCheckJobJpaRepository;
import diplom.platform.ui.dto.AiCheckDtos;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AiCheckProgressSseService {

    private static final long POLL_MS = 450;

    private final AiCheckJobProgressService progressEvents;
    private final AiCheckJobJpaRepository jobs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aicheck-progress-sse");
                t.setDaemon(true);
                return t;
            });

    public AiCheckProgressSseService(AiCheckJobProgressService progressEvents, AiCheckJobJpaRepository jobs) {
        this.progressEvents = progressEvents;
        this.jobs = jobs;
    }

    public SseEmitter open(UUID jobId, long afterId) {
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(45));
        final long[] last = {afterId};
        final int[] idleWhileTerminal = {0};
        /** Последний момент, когда в сокет ушли байты (событие или keep-alive). Иначе прокси рвёт SSE за ~60с. */
        final long[] lastOutboundAt = {System.currentTimeMillis()};

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<AiCheckDtos.AiCheckProgressEventDto> batch = progressEvents.eventsAfter(jobId, last[0]);
                AiCheckJobEntity job = jobs.findById(jobId).orElse(null);
                if (job == null) {
                    emitter.complete();
                    return;
                }
                for (AiCheckDtos.AiCheckProgressEventDto ev : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(ev.id())).data(ev));
                    last[0] = Math.max(last[0], ev.id());
                    lastOutboundAt[0] = System.currentTimeMillis();
                }
                boolean terminal = isTerminal(job.getStatus());
                if (!terminal && batch.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (now - lastOutboundAt[0] > TimeUnit.SECONDS.toMillis(12)) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                        lastOutboundAt[0] = now;
                    }
                }
                if (terminal) {
                    if (batch.isEmpty()) {
                        idleWhileTerminal[0]++;
                    } else {
                        idleWhileTerminal[0] = 0;
                    }
                    if (idleWhileTerminal[0] >= 5) {
                        emitter.complete();
                    }
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, 0, POLL_MS, TimeUnit.MILLISECONDS);

        Runnable cancel = () -> future.cancel(false);
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(e -> cancel.run());
        return emitter;
    }

    private static boolean isTerminal(AiCheckJobEntity.Status s) {
        return s == AiCheckJobEntity.Status.DONE
                || s == AiCheckJobEntity.Status.FAILED
                || s == AiCheckJobEntity.Status.CANCELLED;
    }
}

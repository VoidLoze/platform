package diplom.platform.infrastructure.events;

import diplom.platform.evaluation.infrastructure.OutboxEventEntity;
import diplom.platform.evaluation.infrastructure.OutboxEventJpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(name = "platform.redis.enabled", havingValue = "true")
public class OutboxDispatcher {
    private final OutboxEventJpaRepository outbox;
    private final StringRedisTemplate redis;

    public OutboxDispatcher(OutboxEventJpaRepository outbox, StringRedisTemplate redis) {
        this.outbox = outbox;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${platform.outbox.delay-ms:3000}")
    @Transactional
    public void dispatch() {
        List<OutboxEventEntity> events = outbox.findTop100ByProcessedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEventEntity event : events) {
            redis.convertAndSend("platform.events", event.getEventType() + "|" + event.getPayload());
            event.setProcessedAt(OffsetDateTime.now());
        }
    }
}

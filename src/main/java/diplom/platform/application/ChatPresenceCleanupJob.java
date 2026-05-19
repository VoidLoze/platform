package diplom.platform.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(name = "platform.redis.enabled", havingValue = "true")
public class ChatPresenceCleanupJob {
    private static final String ROOM_COUNT_KEY_PREFIX = "platform:chat:presence:room:counts:";
    private static final String ROOM_USERS_KEY_PREFIX = "platform:chat:presence:room:users:";
    private static final String ROOM_LAST_SEEN_KEY_PREFIX = "platform:chat:presence:room:last-seen:";

    private final StringRedisTemplate redis;
    private final long roomKeysTtlSeconds;

    public ChatPresenceCleanupJob(StringRedisTemplate redis, @Value("${platform.chat.presence.room-ttl-seconds:86400}") long roomKeysTtlSeconds) {
        this.redis = redis;
        this.roomKeysTtlSeconds = roomKeysTtlSeconds;
    }

    @Scheduled(fixedDelayString = "${platform.chat.presence.cleanup-delay-ms:60000}")
    public void cleanup() {
        Set<String> countKeys = redis.keys(ROOM_COUNT_KEY_PREFIX + "*");
        if (countKeys == null || countKeys.isEmpty()) {
            return;
        }
        for (String countKey : countKeys) {
            String roomId = countKey.substring(ROOM_COUNT_KEY_PREFIX.length());
            String usersKey = ROOM_USERS_KEY_PREFIX + roomId;
            String lastSeenKey = ROOM_LAST_SEEN_KEY_PREFIX + roomId;
            redis.expire(countKey, java.time.Duration.ofSeconds(roomKeysTtlSeconds));
            redis.expire(usersKey, java.time.Duration.ofSeconds(roomKeysTtlSeconds));
            redis.expire(lastSeenKey, java.time.Duration.ofSeconds(roomKeysTtlSeconds));
            Set<Object> userIds = redis.opsForHash().keys(countKey);
            if (userIds == null || userIds.isEmpty()) {
                redis.delete(countKey);
                redis.delete(usersKey);
                redis.delete(lastSeenKey);
                continue;
            }
            for (Object userIdObj : userIds) {
                String userId = String.valueOf(userIdObj);
                Object raw = redis.opsForHash().get(countKey, userId);
                long count = raw == null ? 0L : Long.parseLong(String.valueOf(raw));
                if (count <= 0) {
                    redis.opsForHash().delete(countKey, userId);
                    redis.opsForSet().remove(usersKey, userId);
                }
            }
            Long size = redis.opsForHash().size(countKey);
            if (size == null || size == 0) {
                redis.delete(countKey);
                redis.delete(usersKey);
                redis.delete(lastSeenKey);
            }
        }
    }
}

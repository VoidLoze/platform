package diplom.platform.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class ChatPresenceService {
    private static final String SESSION_KEY_PREFIX = "platform:chat:presence:session:";
    private static final String ROOM_COUNT_KEY_PREFIX = "platform:chat:presence:room:counts:";
    private static final String ROOM_USERS_KEY_PREFIX = "platform:chat:presence:room:users:";
    private static final String ROOM_LAST_SEEN_KEY_PREFIX = "platform:chat:presence:room:last-seen:";

    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> roomOnlineUsers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, OffsetDateTime>> roomLastSeen = new ConcurrentHashMap<>();

    public ChatPresenceService(StringRedisTemplate redis, @Value("${platform.redis.enabled:false}") boolean redisEnabled) {
        this.redis = redis;
        this.redisEnabled = redisEnabled;
    }

    public void subscribe(String sessionId, UUID roomId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        if (redisEnabled) {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;
            String roomCountKey = ROOM_COUNT_KEY_PREFIX + roomId;
            String roomUsersKey = ROOM_USERS_KEY_PREFIX + roomId;
            String roomLastSeenKey = ROOM_LAST_SEEN_KEY_PREFIX + roomId;
            redis.opsForValue().set(sessionKey, roomId + ":" + userId, 6, TimeUnit.HOURS);
            Long count = redis.opsForHash().increment(roomCountKey, userId.toString(), 1);
            if (count != null && count > 0) {
                redis.opsForSet().add(roomUsersKey, userId.toString());
            }
            redis.opsForHash().put(roomLastSeenKey, userId.toString(), String.valueOf(now.toInstant().toEpochMilli()));
            return;
        }
        sessions.put(sessionId, new SessionInfo(roomId, userId));
        roomOnlineUsers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
        roomLastSeen.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>()).put(userId, now);
    }

    public UUID disconnect(String sessionId) {
        OffsetDateTime now = OffsetDateTime.now();
        if (redisEnabled) {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;
            String stored = redis.opsForValue().get(sessionKey);
            if (stored == null || !stored.contains(":")) {
                return null;
            }
            redis.delete(sessionKey);
            String[] parts = stored.split(":", 2);
            UUID roomId = UUID.fromString(parts[0]);
            UUID userId = UUID.fromString(parts[1]);
            String roomCountKey = ROOM_COUNT_KEY_PREFIX + roomId;
            String roomUsersKey = ROOM_USERS_KEY_PREFIX + roomId;
            String roomLastSeenKey = ROOM_LAST_SEEN_KEY_PREFIX + roomId;
            Long count = redis.opsForHash().increment(roomCountKey, userId.toString(), -1);
            if (count == null || count <= 0) {
                redis.opsForHash().delete(roomCountKey, userId.toString());
                redis.opsForSet().remove(roomUsersKey, userId.toString());
            }
            redis.opsForHash().put(roomLastSeenKey, userId.toString(), String.valueOf(now.toInstant().toEpochMilli()));
            return roomId;
        }
        SessionInfo removed = sessions.remove(sessionId);
        if (removed == null) {
            return null;
        }
        Set<UUID> users = roomOnlineUsers.get(removed.roomId());
        if (users == null) {
            return removed.roomId();
        }
        users.remove(removed.userId());
        if (users.isEmpty()) {
            roomOnlineUsers.remove(removed.roomId());
        }
        roomLastSeen.computeIfAbsent(removed.roomId(), k -> new ConcurrentHashMap<>()).put(removed.userId(), now);
        return removed.roomId();
    }

    public List<UUID> getOnlineUsers(UUID roomId) {
        if (redisEnabled) {
            Set<String> users = redis.opsForSet().members(ROOM_USERS_KEY_PREFIX + roomId);
            if (users == null || users.isEmpty()) {
                return List.of();
            }
            return users.stream().map(UUID::fromString).toList();
        }
        return new ArrayList<>(roomOnlineUsers.getOrDefault(roomId, Set.of()));
    }

    public Map<UUID, OffsetDateTime> getLastSeenByUser(UUID roomId) {
        if (redisEnabled) {
            Map<Object, Object> values = redis.opsForHash().entries(ROOM_LAST_SEEN_KEY_PREFIX + roomId);
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            Map<UUID, OffsetDateTime> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : values.entrySet()) {
                try {
                    UUID userId = UUID.fromString(String.valueOf(entry.getKey()));
                    long epochMs = Long.parseLong(String.valueOf(entry.getValue()));
                    result.put(userId, OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneOffset.UTC));
                } catch (Exception ignored) {
                    // ignore malformed records
                }
            }
            return result;
        }
        return new HashMap<>(roomLastSeen.getOrDefault(roomId, Map.of()));
    }

    private record SessionInfo(UUID roomId, UUID userId) {}
}

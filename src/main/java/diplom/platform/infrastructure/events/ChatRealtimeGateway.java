package diplom.platform.infrastructure.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.ui.dto.ChatMessageDto;
import diplom.platform.ui.dto.ChatPresenceDto;
import diplom.platform.ui.dto.ChatReadReceiptDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class ChatRealtimeGateway {
    private static final String CHAT_REALTIME_CHANNEL = "platform.chat.realtime";

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean redisEnabled;

    public ChatRealtimeGateway(SimpMessagingTemplate messagingTemplate, StringRedisTemplate redis, ObjectMapper objectMapper,
                               @Value("${platform.redis.enabled:false}") boolean redisEnabled) {
        this.messagingTemplate = messagingTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisEnabled = redisEnabled;
    }

    public void broadcastMessage(ChatMessageDto dto) {
        if (!redisEnabled) {
            messagingTemplate.convertAndSend("/topic/rooms/" + dto.roomId(), dto);
            return;
        }
        publish("message", dto.roomId(), dto);
    }

    public void broadcastReadReceipt(UUID roomId, ChatReadReceiptDto dto) {
        if (!redisEnabled) {
            messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/reads", dto);
            return;
        }
        publish("read", roomId, dto);
    }

    public void broadcastPresence(ChatPresenceDto dto) {
        if (!redisEnabled) {
            messagingTemplate.convertAndSend("/topic/rooms/" + dto.roomId() + "/presence", dto);
            return;
        }
        publish("presence", dto.roomId(), dto);
    }

    public void fanoutLocal(String eventType, UUID roomId, Object payload) {
        if ("message".equals(eventType)) {
            messagingTemplate.convertAndSend("/topic/rooms/" + roomId, payload);
            return;
        }
        if ("read".equals(eventType)) {
            messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/reads", payload);
            return;
        }
        if ("presence".equals(eventType)) {
            messagingTemplate.convertAndSend("/topic/rooms/" + roomId + "/presence", payload);
        }
    }

    private void publish(String eventType, UUID roomId, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "eventType", eventType,
                    "roomId", roomId,
                    "payload", payload
            ));
            redis.convertAndSend(CHAT_REALTIME_CHANNEL, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize realtime event", e);
        }
    }
}

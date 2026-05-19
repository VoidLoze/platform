package diplom.platform.infrastructure.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.ui.dto.ChatMessageDto;
import diplom.platform.ui.dto.ChatPresenceDto;
import diplom.platform.ui.dto.ChatReadReceiptDto;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChatRealtimeListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ChatRealtimeListener.class);
    private final ObjectMapper objectMapper;
    private final ChatRealtimeGateway gateway;

    public ChatRealtimeListener(ObjectMapper objectMapper, ChatRealtimeGateway gateway) {
        this.objectMapper = objectMapper;
        this.gateway = gateway;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            String eventType = root.path("eventType").asText();
            UUID roomId = UUID.fromString(root.path("roomId").asText());
            JsonNode payloadNode = root.path("payload");
            switch (eventType) {
                case "message" -> gateway.fanoutLocal(eventType, roomId, objectMapper.treeToValue(payloadNode, ChatMessageDto.class));
                case "read" -> gateway.fanoutLocal(eventType, roomId, objectMapper.treeToValue(payloadNode, ChatReadReceiptDto.class));
                case "presence" -> gateway.fanoutLocal(eventType, roomId, objectMapper.treeToValue(payloadNode, ChatPresenceDto.class));
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("Chat realtime event handling failed: {}", e.getMessage());
        }
    }
}

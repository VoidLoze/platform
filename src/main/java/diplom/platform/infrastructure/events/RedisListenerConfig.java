package diplom.platform.infrastructure.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(name = "platform.redis.enabled", havingValue = "true")
public class RedisListenerConfig {
    @Bean
    RedisMessageListenerContainer redisContainer(
            RedisConnectionFactory connectionFactory,
            NotificationListener notificationListener,
            ChatRealtimeListener chatRealtimeListener
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notificationListener, new PatternTopic("platform.events"));
        container.addMessageListener(chatRealtimeListener, new PatternTopic("platform.chat.realtime"));
        return container;
    }
}

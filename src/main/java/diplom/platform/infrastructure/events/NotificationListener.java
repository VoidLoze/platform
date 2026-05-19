package diplom.platform.infrastructure.events;

import diplom.platform.evaluation.domain.NotificationService;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener implements MessageListener {
    private final NotificationService notificationService;

    public NotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());
        if (payload.startsWith("AssignmentSubmitted|")) {
            notificationService.notifyUser("student@example.com", "Лабораторная отправлена на проверку.");
        }
        if (payload.startsWith("ReviewGenerated|")) {
            notificationService.notifyUser("teacher@example.com", "AI review готов.");
        }
    }
}

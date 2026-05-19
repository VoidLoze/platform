package diplom.platform.infrastructure.notification;

import diplom.platform.evaluation.domain.NotificationService;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService implements NotificationService {
    private final JavaMailSender sender;

    public EmailNotificationService(JavaMailSender sender) {
        this.sender = sender;
    }

    @Override
    public void notifyUser(String email, String message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(email);
        mail.setSubject("Platform notification");
        mail.setText(message);
        sender.send(mail);
    }
}

package diplom.platform.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Секреты для публичной регистрации привилегированных ролей.
 * Если секрет для роли пустой — регистрация с этой ролью через {@code /api/v1/users/register} недоступна.
 */
@ConfigurationProperties(prefix = "platform.registration")
public class RegistrationProperties {

    /**
     * Код приглашения для регистрации как преподаватель (раздаётся владельцем платформы).
     */
    private String teacherSecret = "";

    /**
     * Код приглашения для регистрации как администратор (строго ограниченный круг).
     */
    private String adminSecret = "";

    public String getTeacherSecret() {
        return teacherSecret;
    }

    public void setTeacherSecret(String teacherSecret) {
        this.teacherSecret = teacherSecret != null ? teacherSecret : "";
    }

    public String getAdminSecret() {
        return adminSecret;
    }

    public void setAdminSecret(String adminSecret) {
        this.adminSecret = adminSecret != null ? adminSecret : "";
    }
}

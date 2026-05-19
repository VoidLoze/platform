package diplom.platform.infrastructure.bootstrap;

import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("docker")
public class DemoDataLoader implements CommandLineRunner {
    private final UserJpaRepository users;
    private final PasswordEncoder encoder;

    public DemoDataLoader(UserJpaRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) {
            return;
        }
        users.save(user("Анна", "Преподаватель", null, "teacher@demo.local", UserRole.ROLE_TEACHER));
        users.save(user("Иван", "Студент", null, "student@demo.local", UserRole.ROLE_STUDENT));
        users.save(user("Админ", "Системный", null, "admin@demo.local", UserRole.ROLE_ADMIN));
    }

    private UserEntity user(String first, String last, String middle, String email, UserRole role) {
        UserEntity u = new UserEntity();
        u.setFirstName(first);
        u.setLastName(last);
        u.setMiddleName(middle);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode("password"));
        u.setRole(role);
        return u;
    }
}

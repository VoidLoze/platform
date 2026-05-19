package diplom.platform.infrastructure.bootstrap;

import diplom.tools.GenerateRegistrationSecrets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code java -jar platform.jar --generate-registration-secrets} — печать секретов и завершение JVM
 * (удобно в Docker-образе без Maven).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistrationSecretsCliApplicationRunner implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    public RegistrationSecretsCliApplicationRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("generate-registration-secrets")) {
            return;
        }
        GenerateRegistrationSecrets.printSecrets(System.out);
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }
}

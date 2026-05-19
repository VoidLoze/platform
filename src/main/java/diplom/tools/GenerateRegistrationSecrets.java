package diplom.tools;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Генерация криптостойких секретов для {@code platform.registration.*} без поднятия Spring.
 * <p>
 * Запуск: {@code mvn -q compile exec:java -Dexec.mainClass=diplom.tools.GenerateRegistrationSecrets}
 * или {@code java -jar … --generate-registration-secrets}
 */
public final class GenerateRegistrationSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private GenerateRegistrationSecrets() {
    }

    public static void main(String[] args) {
        printSecrets(System.out);
    }

    /** Печать в заданный поток (stdout или лог). */
    public static void printSecrets(java.io.PrintStream out) {
        String teacher = randomSecret();
        String admin = randomSecret();
        out.println();
        out.println("# Скопируйте в .env, Kubernetes Secret или переменные CI (не коммить значения).");
        out.println("PLATFORM_REGISTRATION_TEACHER_SECRET=" + teacher);
        out.println("PLATFORM_REGISTRATION_ADMIN_SECRET=" + admin);
        out.println();
        out.println("# Либо в application-*.properties / Helm values:");
        out.println("platform.registration.teacher-secret=" + teacher);
        out.println("platform.registration.admin-secret=" + admin);
        out.println();
    }

    /** URL-safe Base64 без padding, 32 байта энтропии на секрет. */
    public static String randomSecret() {
        byte[] buf = new byte[BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}

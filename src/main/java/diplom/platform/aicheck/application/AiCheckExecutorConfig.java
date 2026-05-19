package diplom.platform.aicheck.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AiCheckExecutorConfig {

    /**
     * Пул для AI-проверок: ограниченный, чтобы не упереться в лимиты провайдеров и не съесть память.
     */
    @Bean(name = "aiCheckExecutor")
    public Executor aiCheckExecutor(@Value("${platform.aicheck.executor.core-size:2}") int coreSize,
                                    @Value("${platform.aicheck.executor.max-size:6}") int maxSize,
                                    @Value("${platform.aicheck.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setThreadNamePrefix("ai-check-");
        exec.initialize();
        return exec;
    }
}

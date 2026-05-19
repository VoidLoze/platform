package diplom.platform.aicheck.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class AiCheckDockerRunnerClient {

    private static final Logger log = LoggerFactory.getLogger(AiCheckDockerRunnerClient.class);

    private final RestTemplate restTemplate;
    private final URI runUri;
    private final String secret;

    public AiCheckDockerRunnerClient(
            @Value("${platform.aicheck.runner.url:}") String baseUrl,
            @Value("${platform.aicheck.runner.secret:}") String secret
    ) {
        this.secret = secret == null ? "" : secret.trim();
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            this.restTemplate = null;
            this.runUri = null;
        } else {
            String normalized = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            this.runUri = URI.create(normalized + "/v1/run");
            this.restTemplate = new RestTemplate();
        }
    }

    public boolean enabled() {
        return restTemplate != null && runUri != null && !secret.isEmpty();
    }

    RunnerRunResult remoteRun(byte[] workspaceZip, String image, String command, String cpus, String memory) {
        return remoteRun(workspaceZip, image, command, cpus, memory, "");
    }

    RunnerRunResult remoteRun(byte[] workspaceZip, String image, String command, String cpus, String memory, String stdinText) {
        if (restTemplate == null || runUri == null) {
            throw new IllegalStateException("Runner не настроен");
        }
        // RestTemplate + MultiValueMap: FormHttpMessageConverter writes proper multipart boundary.
        // RestClient with MultipartBodyBuilder did not emit a body in Spring Boot 4 (runner saw HTTP 422).
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("workspace", new ByteArrayResource(workspaceZip) {
            @Override
            public String getFilename() {
                return "workspace.zip";
            }
        });
        parts.add("image", image);
        parts.add("command", command);
        parts.add("cpus", cpus);
        parts.add("memory", memory);
        // Всегда передаём поле stdin: иначе runner может не подключать поток ввода к процессу,
        // и Scanner в лабах падает с NoSuchElementException даже при пустой строке (нужен явный EOF-пайп).
        String stdin = stdinText == null ? "" : stdinText;
        parts.add("stdin", stdin);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Platform-Aicheck-Runner-Token", secret);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(parts, headers);
        try {
            ResponseEntity<RunnerRunResult> resp = restTemplate.exchange(
                    runUri, HttpMethod.POST, entity, RunnerRunResult.class);
            RunnerRunResult body = resp.getBody();
            if (body == null) {
                throw new IllegalStateException("Runner вернул пустое тело ответа");
            }
            return body;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.warn("Runner HTTP {}: {}", e.getStatusCode().value(), body);
            throw new IllegalStateException(
                    "Runner error: HTTP " + e.getStatusCode().value()
                            + (body == null || body.isBlank() ? "" : (": " + body)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunnerRunResult(int exitCode, String output, String status) {
    }
}

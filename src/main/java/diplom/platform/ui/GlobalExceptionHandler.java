package diplom.platform.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** Race between duplicate checks, or constraint outside registerUser. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException ex) {
        String m = ex.getMostSpecificCause().getMessage();
        if (m != null && m.contains("users_email_key")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Этот email уже зарегистрирован"));
        }
        log.warn("Data integrity violation", ex);
        return ResponseEntity.badRequest().body(Map.of("error", "Запрос противоречит ограничениям данных"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<Map<String, String>> unauthorized(AuthenticationCredentialsNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("error", msg.isEmpty() ? "validation_failed" : msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> notReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request_body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_parameter"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> missingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "missing_part"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException ex) {
        log.debug("Upload rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Файл превышает допустимый размер загрузки (настройте spring.servlet.multipart.max-file-size)."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String msg = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    /**
     * Клиент закрыл соединение (refresh/закрытие вкладки/таймаут прокси) во время записи ответа.
     * Это не серверная ошибка, не нужно превращать в 500 и засорять error-логи.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void clientAbort(Exception ex, HttpServletRequest request) {
        log.debug("Client aborted connection for {} {}", request.getMethod(), request.getRequestURI());
    }

    /**
     * Клиент закрыл сокет пока сервер ещё сериализовал JSON (большой ответ / медленная сеть).
     * Не считаем это внутренней ошибкой приложения.
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void messageNotWritable(HttpMessageNotWritableException ex, HttpServletRequest request) {
        if (isLikelyClientAbort(ex)) {
            log.debug("Client closed connection during response write for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        } else {
            log.warn("Failed to write HTTP message for {} {}", request.getMethod(), request.getRequestURI(), ex);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> fallback(Exception ex, HttpServletRequest request) {
        if (isLikelyClientAbort(ex)) {
            log.debug("Client abort (wrapped) for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "client_disconnected"));
        }
        log.error("Unhandled {} {}", request.getMethod(), request.getRequestURI(), ex);
        String msg = ex.getMessage() != null ? ex.getClass().getSimpleName() + ": " + ex.getMessage() : ex.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal_error", "detail", msg));
    }

    private static boolean isLikelyClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null) {
                String lower = m.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("response not usable after response errors")) {
                    return true;
                }
            }
            if (t instanceof java.io.IOException && m != null && m.toLowerCase().contains("broken pipe")) {
                return true;
            }
        }
        return false;
    }
}

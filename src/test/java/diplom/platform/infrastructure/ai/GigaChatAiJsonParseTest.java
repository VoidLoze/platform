package diplom.platform.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GigaChatAiJsonParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesJsonWithInvalidBackslashBeforeParenthesis() throws Exception {
        // Typical GigaChat mistake: LaTeX or markers like \( x \) without escaping backslashes
        String invalid = "{\"questions\":[{\"text\":\"Решение \\(x+1\\)\",\"difficulty\":\"medium\","
                + "\"options\":[\"A\",\"B\",\"C\",\"D\"],\"correctAnswer\":\"A\"}]}";
        JsonNode root = invokeTryParse(invalid);
        assertEquals(1, root.path("questions").size());
        assertFalse(root.path("questions").get(0).path("text").asText().isBlank());
    }

    @Test
    void parsesJsonWithLonelyBackslashBeforeComma() throws Exception {
        String invalid = "{\"questions\":[{\"text\":\"Ответ A\",\"difficulty\":\"easy\","
                + "\"options\":[\"1\\%\",\"B\",\"C\",\"D\"],\"correctAnswer\":\"1\\%\"}]}";
        JsonNode root = invokeTryParse(invalid);
        assertEquals(1, root.path("questions").size());
        assertEquals("1%", root.path("questions").get(0).path("correctAnswer").asText());
    }

    private JsonNode invokeTryParse(String json) throws Exception {
        GigaChatAIService service = new GigaChatAIService(
                "https://example.invalid",
                "https://example.invalid",
                "GigaChat",
                "demo-token",
                "GIGACHAT_API_PERS",
                false,
                "https://example.invalid",
                "deepseek-chat",
                "",
                mapper
        );
        Method m = GigaChatAIService.class.getDeclaredMethod("tryParseJsonTree", String.class);
        m.setAccessible(true);
        try {
            return (JsonNode) m.invoke(service, json);
        } catch (Exception e) {
            if (e.getCause() instanceof JsonProcessingException jpe) {
                throw jpe;
            }
            throw e;
        }
    }
}

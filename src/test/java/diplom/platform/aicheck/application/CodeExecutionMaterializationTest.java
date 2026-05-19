package diplom.platform.aicheck.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeExecutionMaterializationTest {

    @Test
    void normalizeStudentText_stripsPlaceholder() {
        assertNull(AiCheckLabWorkSubmissionResolver.normalizeStudentText("См. вложенный файл"));
        assertEquals("hello", AiCheckLabWorkSubmissionResolver.normalizeStudentText("  hello  "));
    }

    @Test
    void extractCompilableSource_stripsFileMarkerAndPlaceholder() {
        String polluted = "См. вложенный файл\n\n--- файл: student-answers/x.java ---\n"
                + "package lab07;\n\npublic class Lab71 { }";
        String clean = CodeExecutionService.extractCompilableSource(polluted);
        assertTrue(clean.startsWith("package lab07"));
        assertTrue(clean.contains("public class Lab71"));
    }
}

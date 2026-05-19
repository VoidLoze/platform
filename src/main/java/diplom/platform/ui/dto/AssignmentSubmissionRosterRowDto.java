package diplom.platform.ui.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Строка журнала сдач по заданию: один ученик из группы + последняя текстовая отправка и результат теста (если есть).
 */
public record AssignmentSubmissionRosterRowDto(
        UUID studentId,
        String studentName,
        String studentEmail,
        UUID labWorkId,
        String labStatus,
        OffsetDateTime labSubmittedAt,
        Double labFinalGrade,
        Double testScore,
        boolean testTaken,
        int labSubmissionCount,
        boolean assignmentHasTest
) {
}

package diplom.platform.ui.dto;

import java.util.UUID;

public record StudentProgressDto(
        UUID studentId,
        String studentName,
        long totalSubmitted,
        long gradedCount,
        double averageGrade
) {
}

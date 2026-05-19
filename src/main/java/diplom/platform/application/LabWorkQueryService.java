package diplom.platform.application;

import diplom.platform.evaluation.infrastructure.*;
import diplom.platform.identity.domain.UserRole;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.ui.dto.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LabWorkQueryService {
    private final LabWorkJpaRepository labWorks;
    private final AIReviewJpaRepository reviews;
    private final GeneratedTestJpaRepository tests;

    public LabWorkQueryService(LabWorkJpaRepository labWorks, AIReviewJpaRepository reviews, GeneratedTestJpaRepository tests) {
        this.labWorks = labWorks;
        this.reviews = reviews;
        this.tests = tests;
    }

    @Transactional(readOnly = true)
    public List<LabWorkSummaryDto> listMine(PlatformUser user) {
        if (user.role() != UserRole.ROLE_STUDENT) {
            throw new AccessDeniedException("Only students have lab submissions list");
        }
        return labWorks.findMineWithAssignment(user.id()).stream()
                .map(lw -> new LabWorkSummaryDto(
                        lw.getId(),
                        lw.getAssignment().getId(),
                        lw.getAssignment().getTopicTitle(),
                        lw.getStatus(),
                        lw.getSubmissionTime(),
                        lw.getFinalGradeValue(),
                        lw.getFinalGradeLetter()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public LabWorkDetailDto getDetail(UUID labWorkId, PlatformUser user) {
        LabWorkEntity lw = labWorks.findDetailById(labWorkId).orElseThrow(() -> new IllegalArgumentException("Lab work not found"));
        authorizeView(lw, user);
        boolean showAnswers = user.role() == UserRole.ROLE_TEACHER || user.role() == UserRole.ROLE_ADMIN;

        AIReviewDto reviewDto = reviews.findByLabWorkId(labWorkId)
                .map(r -> new AIReviewDto(r.getId(), r.getScoreValue(), r.getMaxScore(), r.getSummary(), r.getDetailedFeedback(), r.getRecommendations()))
                .orElse(null);

        GeneratedTestDto testDto = tests.findWithQuestionsByLabWorkId(labWorkId)
                .map(t -> new GeneratedTestDto(t.getId(), t.getMaxScore(), t.getQuestions().stream()
                        .map(q -> new QuestionDto(
                                q.getId(),
                                q.getText(),
                                q.getDifficulty(),
                                showAnswers ? q.getCorrectAnswer() : null
                        ))
                        .toList()))
                .orElse(null);

        String studentDisplayName = null;
        if (user.role() == UserRole.ROLE_TEACHER || user.role() == UserRole.ROLE_ADMIN) {
            studentDisplayName = lw.getStudent().getFirstName() + " " + lw.getStudent().getLastName();
        }

        return new LabWorkDetailDto(
                lw.getId(),
                lw.getAssignment().getId(),
                lw.getAssignment().getTopicTitle(),
                lw.getAssignment().getCourse().getId(),
                lw.getAssignment().getCourse().getTitle(),
                lw.getStatus(),
                lw.getTextContent(),
                lw.getAttachmentKey(),
                lw.getLanguage(),
                lw.getSubmissionTime(),
                lw.getFinalGradeValue(),
                lw.getFinalGradeLetter(),
                reviewDto,
                testDto,
                studentDisplayName,
                lw.getAssignment().getMaxScore(),
                lw.getAssignment().getTopicDescription()
        );
    }

    private void authorizeView(LabWorkEntity lw, PlatformUser user) {
        if (user.role() == UserRole.ROLE_ADMIN) {
            return;
        }
        if (user.role() == UserRole.ROLE_TEACHER) {
            var course = lw.getAssignment().getCourse();
            UUID ownerId = course.getOwner() == null ? null : course.getOwner().getId();
            if (ownerId != null && ownerId.equals(user.id())) {
                return;
            }
            throw new AccessDeniedException("Forbidden");
        }
        if (user.role() == UserRole.ROLE_STUDENT && lw.getStudent().getId().equals(user.id())) {
            return;
        }
        throw new AccessDeniedException("Forbidden");
    }
}

package diplom.platform.evaluation.infrastructure;

import diplom.platform.evaluation.domain.LabWorkStatus;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "lab_works")
public class LabWorkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assignment_id")
    private AssignmentEntity assignment;

    @ManyToOne(optional = false)
    @JoinColumn(name = "student_id")
    private UserEntity student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LabWorkStatus status;

    @Column(columnDefinition = "TEXT")
    private String textContent;
    private String attachmentKey;
    private String language;
    private OffsetDateTime submissionTime;

    private Double finalGradeValue;
    private String finalGradeLetter;

    public UUID getId() { return id; }
    public AssignmentEntity getAssignment() { return assignment; }
    public void setAssignment(AssignmentEntity assignment) { this.assignment = assignment; }
    public UserEntity getStudent() { return student; }
    public void setStudent(UserEntity student) { this.student = student; }
    public LabWorkStatus getStatus() { return status; }
    public void setStatus(LabWorkStatus status) { this.status = status; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public String getAttachmentKey() { return attachmentKey; }
    public void setAttachmentKey(String attachmentKey) { this.attachmentKey = attachmentKey; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public OffsetDateTime getSubmissionTime() { return submissionTime; }
    public void setSubmissionTime(OffsetDateTime submissionTime) { this.submissionTime = submissionTime; }
    public Double getFinalGradeValue() { return finalGradeValue; }
    public void setFinalGradeValue(Double finalGradeValue) { this.finalGradeValue = finalGradeValue; }
    public String getFinalGradeLetter() { return finalGradeLetter; }
    public void setFinalGradeLetter(String finalGradeLetter) { this.finalGradeLetter = finalGradeLetter; }
}

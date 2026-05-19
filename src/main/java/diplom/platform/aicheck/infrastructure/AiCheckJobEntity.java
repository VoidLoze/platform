package diplom.platform.aicheck.infrastructure;

import diplom.platform.aicheck.domain.AiCheckSourceType;
import diplom.platform.aicheck.domain.AiCheckSubject;
import diplom.platform.evaluation.infrastructure.LabWorkEntity;
import diplom.platform.identity.infrastructure.UserEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_check_jobs")
public class AiCheckJobEntity {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    private UserEntity requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lab_work_id")
    private LabWorkEntity labWork;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AiCheckSubject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private AiCheckSourceType sourceType;

    @Column(length = 500)
    private String title;

    @Column(name = "custom_instructions", columnDefinition = "TEXT")
    private String customInstructions;

    @Column(name = "student_text", columnDefinition = "TEXT")
    private String studentText;

    @Column(name = "attachment_file_key", length = 500)
    private String attachmentFileKey;
    @Column(name = "attachment_file_keys_json", columnDefinition = "TEXT")
    private String attachmentFileKeysJson;

    @Column(name = "git_url", length = 1000)
    private String gitUrl;

    @Column(name = "selected_provider", length = 50)
    private String selectedProvider;

    @Column(name = "fallback_chain", length = 500)
    private String fallbackChain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Status status = Status.QUEUED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public UUID getId() { return id; }
    public UserEntity getRequester() { return requester; }
    public void setRequester(UserEntity requester) { this.requester = requester; }
    public LabWorkEntity getLabWork() { return labWork; }
    public void setLabWork(LabWorkEntity labWork) { this.labWork = labWork; }
    public AiCheckSubject getSubject() { return subject; }
    public void setSubject(AiCheckSubject subject) { this.subject = subject; }
    public AiCheckSourceType getSourceType() { return sourceType; }
    public void setSourceType(AiCheckSourceType sourceType) { this.sourceType = sourceType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(String customInstructions) { this.customInstructions = customInstructions; }
    public String getStudentText() { return studentText; }
    public void setStudentText(String studentText) { this.studentText = studentText; }
    public String getAttachmentFileKey() { return attachmentFileKey; }
    public void setAttachmentFileKey(String attachmentFileKey) { this.attachmentFileKey = attachmentFileKey; }
    public String getAttachmentFileKeysJson() { return attachmentFileKeysJson; }
    public void setAttachmentFileKeysJson(String attachmentFileKeysJson) { this.attachmentFileKeysJson = attachmentFileKeysJson; }
    public String getGitUrl() { return gitUrl; }
    public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }
    public String getSelectedProvider() { return selectedProvider; }
    public void setSelectedProvider(String selectedProvider) { this.selectedProvider = selectedProvider; }
    public String getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(String fallbackChain) { this.fallbackChain = fallbackChain; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}

package diplom.platform.aicheck.infrastructure;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_check_job_events")
public class AiCheckJobEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private AiCheckJobEntity job;

    @Column(nullable = false, length = 64)
    private String phase;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 16)
    private String scope;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "test_id", length = 128)
    private String testId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() {
        return id;
    }

    public AiCheckJobEntity getJob() {
        return job;
    }

    public void setJob(AiCheckJobEntity job) {
        this.job = job;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }
}

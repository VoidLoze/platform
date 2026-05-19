package diplom.platform.aicheck.infrastructure;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_check_results")
public class AiCheckResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private AiCheckJobEntity job;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false)
    private double score;

    @Column(name = "max_score", nullable = false)
    private double maxScore;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "detailed_feedback", columnDefinition = "TEXT")
    private String detailedFeedback;

    @Column(name = "strengths_json", columnDefinition = "TEXT")
    private String strengthsJson;

    @Column(name = "issues_json", columnDefinition = "TEXT")
    private String issuesJson;

    @Column(name = "recommendations_json", columnDefinition = "TEXT")
    private String recommendationsJson;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    public UUID getId() { return id; }
    public AiCheckJobEntity getJob() { return job; }
    public void setJob(AiCheckJobEntity job) { this.job = job; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetailedFeedback() { return detailedFeedback; }
    public void setDetailedFeedback(String detailedFeedback) { this.detailedFeedback = detailedFeedback; }
    public String getStrengthsJson() { return strengthsJson; }
    public void setStrengthsJson(String strengthsJson) { this.strengthsJson = strengthsJson; }
    public String getIssuesJson() { return issuesJson; }
    public void setIssuesJson(String issuesJson) { this.issuesJson = issuesJson; }
    public String getRecommendationsJson() { return recommendationsJson; }
    public void setRecommendationsJson(String recommendationsJson) { this.recommendationsJson = recommendationsJson; }
    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
}

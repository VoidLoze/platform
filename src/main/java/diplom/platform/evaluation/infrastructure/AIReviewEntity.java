package diplom.platform.evaluation.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "ai_reviews")
public class AIReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false)
    @JoinColumn(name = "lab_work_id", unique = true)
    private LabWorkEntity labWork;

    private double scoreValue;
    private double maxScore;

    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(columnDefinition = "TEXT")
    private String detailedFeedback;
    @Column(columnDefinition = "TEXT")
    private String recommendations;

    public UUID getId() { return id; }
    public LabWorkEntity getLabWork() { return labWork; }
    public void setLabWork(LabWorkEntity labWork) { this.labWork = labWork; }
    public double getScoreValue() { return scoreValue; }
    public void setScoreValue(double scoreValue) { this.scoreValue = scoreValue; }
    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetailedFeedback() { return detailedFeedback; }
    public void setDetailedFeedback(String detailedFeedback) { this.detailedFeedback = detailedFeedback; }
    public String getRecommendations() { return recommendations; }
    public void setRecommendations(String recommendations) { this.recommendations = recommendations; }
}

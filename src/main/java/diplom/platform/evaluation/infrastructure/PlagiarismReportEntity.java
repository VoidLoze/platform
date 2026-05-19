package diplom.platform.evaluation.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "plagiarism_reports")
public class PlagiarismReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false)
    @JoinColumn(name = "lab_work_id", unique = true)
    private LabWorkEntity labWork;

    private double similarityScore;
    @Column(columnDefinition = "TEXT")
    private String matchedSourcesJson;

    public UUID getId() { return id; }
    public LabWorkEntity getLabWork() { return labWork; }
    public void setLabWork(LabWorkEntity labWork) { this.labWork = labWork; }
    public double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(double similarityScore) { this.similarityScore = similarityScore; }
    public String getMatchedSourcesJson() { return matchedSourcesJson; }
    public void setMatchedSourcesJson(String matchedSourcesJson) { this.matchedSourcesJson = matchedSourcesJson; }
}

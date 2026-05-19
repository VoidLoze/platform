package diplom.platform.learning.infrastructure;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "assignments")
public class AssignmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "course_id")
    private CourseEntity course;

    @Column(nullable = false)
    private String topicTitle;
    @Column(columnDefinition = "TEXT")
    private String topicDescription;
    private String subjectArea;

    @Column(nullable = false)
    private OffsetDateTime dueDate;
    @Column(nullable = false)
    private boolean allowLateSubmission;
    @Column(nullable = false)
    private double latePenaltyPercent;
    @Column(nullable = false)
    private double maxScore;

    public UUID getId() { return id; }
    public CourseEntity getCourse() { return course; }
    public void setCourse(CourseEntity course) { this.course = course; }
    public String getTopicTitle() { return topicTitle; }
    public void setTopicTitle(String topicTitle) { this.topicTitle = topicTitle; }
    public String getTopicDescription() { return topicDescription; }
    public void setTopicDescription(String topicDescription) { this.topicDescription = topicDescription; }
    public String getSubjectArea() { return subjectArea; }
    public void setSubjectArea(String subjectArea) { this.subjectArea = subjectArea; }
    public OffsetDateTime getDueDate() { return dueDate; }
    public void setDueDate(OffsetDateTime dueDate) { this.dueDate = dueDate; }
    public boolean isAllowLateSubmission() { return allowLateSubmission; }
    public void setAllowLateSubmission(boolean allowLateSubmission) { this.allowLateSubmission = allowLateSubmission; }
    public double getLatePenaltyPercent() { return latePenaltyPercent; }
    public void setLatePenaltyPercent(double latePenaltyPercent) { this.latePenaltyPercent = latePenaltyPercent; }
    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }
}

package diplom.platform.learning.infrastructure;

import diplom.platform.identity.infrastructure.UserEntity;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "assignment_test_attempts")
public class AssignmentTestAttemptEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "test_id")
    private AssignmentTestEntity test;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private UserEntity student;

    @Column(nullable = false)
    private double score;

    @Column(nullable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    public UUID getId() { return id; }
    public AssignmentTestEntity getTest() { return test; }
    public void setTest(AssignmentTestEntity test) { this.test = test; }
    public UserEntity getStudent() { return student; }
    public void setStudent(UserEntity student) { this.student = student; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getAnswersJson() { return answersJson; }
    public void setAnswersJson(String answersJson) { this.answersJson = answersJson; }
}

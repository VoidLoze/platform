package diplom.platform.learning.infrastructure;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "assignment_tests")
public class AssignmentTestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private AssignmentEntity assignment;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private double maxScore;

    @OneToMany(mappedBy = "test", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AssignmentTestQuestionEntity> questions = new ArrayList<>();

    public UUID getId() { return id; }
    public AssignmentEntity getAssignment() { return assignment; }
    public void setAssignment(AssignmentEntity assignment) { this.assignment = assignment; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }
    public List<AssignmentTestQuestionEntity> getQuestions() { return questions; }
}

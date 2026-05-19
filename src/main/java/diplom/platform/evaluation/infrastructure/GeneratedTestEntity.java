package diplom.platform.evaluation.infrastructure;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "generated_tests")
public class GeneratedTestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false)
    @JoinColumn(name = "lab_work_id", unique = true)
    private LabWorkEntity labWork;

    private double maxScore;

    @OneToMany(mappedBy = "test", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuestionEntity> questions = new ArrayList<>();

    public UUID getId() { return id; }
    public LabWorkEntity getLabWork() { return labWork; }
    public void setLabWork(LabWorkEntity labWork) { this.labWork = labWork; }
    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }
    public List<QuestionEntity> getQuestions() { return questions; }
}

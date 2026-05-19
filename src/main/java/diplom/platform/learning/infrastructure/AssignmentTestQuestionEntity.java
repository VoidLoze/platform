package diplom.platform.learning.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "assignment_test_questions")
public class AssignmentTestQuestionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "test_id")
    private AssignmentTestEntity test;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(nullable = false)
    private String questionType;

    @Column(columnDefinition = "TEXT")
    private String optionsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(nullable = false)
    private double points;

    public UUID getId() { return id; }
    public AssignmentTestEntity getTest() { return test; }
    public void setTest(AssignmentTestEntity test) { this.test = test; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    public double getPoints() { return points; }
    public void setPoints(double points) { this.points = points; }
}

package diplom.platform.evaluation.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "questions")
public class QuestionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "test_id")
    private GeneratedTestEntity test;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;
    private String difficulty;
    @Column(columnDefinition = "TEXT")
    private String correctAnswer;

    public UUID getId() { return id; }
    public GeneratedTestEntity getTest() { return test; }
    public void setTest(GeneratedTestEntity test) { this.test = test; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getCorrectAnswer() { return correctAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
}

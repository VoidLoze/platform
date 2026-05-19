package diplom.platform.aicheck.infrastructure;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_check_settings")
public class AiCheckSettingsEntity {
    @Id
    private Integer id;

    @Column(name = "default_provider", nullable = false, length = 50)
    private String defaultProvider;

    @Column(name = "fallback_chain", nullable = false, length = 500)
    private String fallbackChain;

    @Column(name = "subject_policy_json", columnDefinition = "TEXT")
    private String subjectPolicyJson;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public String getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(String fallbackChain) { this.fallbackChain = fallbackChain; }
    public String getSubjectPolicyJson() { return subjectPolicyJson; }
    public void setSubjectPolicyJson(String subjectPolicyJson) { this.subjectPolicyJson = subjectPolicyJson; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

package diplom.platform.learning.infrastructure;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "courses")
public class CourseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "avatar_file_key")
    private String avatarFileKey;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private diplom.platform.identity.infrastructure.UserEntity owner;

    @ManyToMany
    @JoinTable(name = "enrollments",
            joinColumns = @JoinColumn(name = "course_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<diplom.platform.identity.infrastructure.UserEntity> students = new HashSet<>();

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Set<diplom.platform.identity.infrastructure.UserEntity> getStudents() { return students; }

    public diplom.platform.identity.infrastructure.UserEntity getOwner() { return owner; }
    public void setOwner(diplom.platform.identity.infrastructure.UserEntity owner) { this.owner = owner; }

    public String getAvatarFileKey() { return avatarFileKey; }
    public void setAvatarFileKey(String avatarFileKey) { this.avatarFileKey = avatarFileKey; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}

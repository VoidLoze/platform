package diplom.platform.identity.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import diplom.platform.identity.domain.UserRole;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String firstName;
    @Column(nullable = false)
    private String lastName;
    private String middleName;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /** Stored file key under {@code platform.files.base-dir}, scope {@code avatar/}. */
    @Column(length = 512)
    private String avatarFileKey;

    @Column(columnDefinition = "text")
    private String bio;

    public UUID getId() { return id; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public String getAvatarFileKey() { return avatarFileKey; }
    public void setAvatarFileKey(String avatarFileKey) { this.avatarFileKey = avatarFileKey; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
}

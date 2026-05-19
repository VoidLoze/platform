package diplom.platform.learning.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "group_post_attachments")
public class GroupPostAttachmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private GroupPostEntity post;

    @Column(name = "file_key", nullable = false, length = 500)
    private String fileKey;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    public UUID getId() { return id; }
    public GroupPostEntity getPost() { return post; }
    public void setPost(GroupPostEntity post) { this.post = post; }
    public String getFileKey() { return fileKey; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
}

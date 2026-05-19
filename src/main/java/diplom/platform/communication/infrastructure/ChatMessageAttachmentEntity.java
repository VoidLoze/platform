package diplom.platform.communication.infrastructure;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "chat_message_attachments")
public class ChatMessageAttachmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private ChatMessageEntity message;

    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(name = "original_name", length = 512)
    private String originalName;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "sort_index", nullable = false)
    private int sortIndex;

    public UUID getId() { return id; }
    public ChatMessageEntity getMessage() { return message; }
    public void setMessage(ChatMessageEntity message) { this.message = message; }
    public String getFileKey() { return fileKey; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public int getSortIndex() { return sortIndex; }
    public void setSortIndex(int sortIndex) { this.sortIndex = sortIndex; }
}

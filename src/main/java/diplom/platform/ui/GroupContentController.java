package diplom.platform.ui;

import diplom.platform.application.GroupContentService;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.CourseDto;
import diplom.platform.ui.dto.GroupMaterialDto;
import diplom.platform.ui.dto.GroupPostDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST для ленты группы (новостей) и общих материалов группы. Маршруты вложены под {@code /api/v1/courses}
 * чтобы переиспользовать существующее ACL по курсу.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}")
public class GroupContentController {

    private final GroupContentService groupContent;

    public GroupContentController(GroupContentService groupContent) {
        this.groupContent = groupContent;
    }

    @GetMapping("/posts")
    @PreAuthorize("isAuthenticated()")
    public List<GroupPostDto> listPosts(@PathVariable UUID courseId) {
        return groupContent.listPosts(courseId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/posts")
    @PreAuthorize("hasRole('TEACHER')")
    public GroupPostDto createPost(@PathVariable UUID courseId, @RequestBody CreatePostRequest req) {
        List<GroupContentService.AttachmentInput> atts = req.attachments() == null
                ? List.of()
                : req.attachments().stream()
                        .map(a -> new GroupContentService.AttachmentInput(
                                a.fileKey(), a.originalName(), a.contentType(), a.sizeBytes()))
                        .toList();
        return groupContent.createPost(courseId, SecurityUtils.requireCurrentUser(), req.title(), req.body(), req.pinned(), atts);
    }

    @DeleteMapping("/posts/{postId}")
    @PreAuthorize("hasRole('TEACHER')")
    public void deletePost(@PathVariable UUID courseId, @PathVariable UUID postId) {
        groupContent.deletePost(postId, SecurityUtils.requireCurrentUser());
    }

    @GetMapping("/materials")
    @PreAuthorize("isAuthenticated()")
    public List<GroupMaterialDto> listMaterials(@PathVariable UUID courseId) {
        return groupContent.listMaterials(courseId, SecurityUtils.requireCurrentUser());
    }

    @PostMapping("/materials/upload")
    @PreAuthorize("hasRole('TEACHER')")
    public GroupMaterialDto uploadMaterial(@PathVariable UUID courseId,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "title", required = false) String title,
                                           @RequestParam(value = "description", required = false) String description) {
        return groupContent.uploadMaterial(courseId, SecurityUtils.requireCurrentUser(), file, title, description);
    }

    @DeleteMapping("/materials/{materialId}")
    @PreAuthorize("hasRole('TEACHER')")
    public void deleteMaterial(@PathVariable UUID courseId, @PathVariable UUID materialId) {
        groupContent.deleteMaterial(materialId, SecurityUtils.requireCurrentUser());
    }

    @PatchMapping("/profile")
    @PreAuthorize("hasRole('TEACHER')")
    public CourseDto updateProfile(@PathVariable UUID courseId, @RequestBody UpdateGroupProfileRequest req) {
        return groupContent.updateGroupProfile(courseId, SecurityUtils.requireCurrentUser(), req.title(), req.description());
    }

    @PostMapping("/avatar")
    @PreAuthorize("hasRole('TEACHER')")
    public CourseDto uploadAvatar(@PathVariable UUID courseId, @RequestParam("file") MultipartFile file) {
        return groupContent.uploadAvatar(courseId, SecurityUtils.requireCurrentUser(), file);
    }

    public record CreatePostRequest(String title, String body, boolean pinned, List<AttachmentRef> attachments) {
    }

    public record AttachmentRef(String fileKey, String originalName, String contentType, Long sizeBytes) {
    }

    public record UpdateGroupProfileRequest(String title, String description) {
    }
}

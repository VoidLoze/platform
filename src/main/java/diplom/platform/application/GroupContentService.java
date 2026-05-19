package diplom.platform.application;

import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.storage.FileStorageService;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import diplom.platform.learning.infrastructure.GroupMaterialEntity;
import diplom.platform.learning.infrastructure.GroupMaterialJpaRepository;
import diplom.platform.learning.infrastructure.GroupPostAttachmentEntity;
import diplom.platform.learning.infrastructure.GroupPostEntity;
import diplom.platform.learning.infrastructure.GroupPostJpaRepository;
import diplom.platform.ui.dto.CourseDto;
import diplom.platform.ui.dto.GroupMaterialDto;
import diplom.platform.ui.dto.GroupPostDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Сервис ленты группы: новости с прикрепляемыми файлами и общие учебные материалы.
 * Доступ контролируется через {@link CourseService}: только владелец/админ может писать,
 * читать может любой участник группы или владелец/админ.
 */
@Service
public class GroupContentService {
    private final CourseService courseService;
    private final CourseJpaRepository courses;
    private final UserJpaRepository users;
    private final GroupPostJpaRepository posts;
    private final GroupMaterialJpaRepository materials;
    private final FileStorageService files;

    public GroupContentService(CourseService courseService, CourseJpaRepository courses, UserJpaRepository users,
                               GroupPostJpaRepository posts, GroupMaterialJpaRepository materials,
                               FileStorageService files) {
        this.courseService = courseService;
        this.courses = courses;
        this.users = users;
        this.posts = posts;
        this.materials = materials;
        this.files = files;
    }

    @Transactional
    public CourseDto updateGroupProfile(UUID courseId, PlatformUser viewer, String title, String description) {
        courseService.assertTeacherOwnsOrAdmin(courseId, viewer);
        CourseEntity course = courseService.getCourse(courseId);
        if (title != null && !title.isBlank()) {
            course.setTitle(title.trim());
        }
        if (description != null) {
            String normalized = description.isBlank() ? null : description.trim();
            course.setDescription(normalized);
        }
        CourseEntity saved = courses.save(course);
        return toCourseDto(saved);
    }

    @Transactional
    public CourseDto uploadAvatar(UUID courseId, PlatformUser viewer, MultipartFile file) {
        courseService.assertTeacherOwnsOrAdmin(courseId, viewer);
        CourseEntity course = courseService.getCourse(courseId);
        String key = files.save(file, "courses/" + courseId + "/avatar");
        course.setAvatarFileKey(key);
        return toCourseDto(courses.save(course));
    }

    @Transactional(readOnly = true)
    public List<GroupPostDto> listPosts(UUID courseId, PlatformUser viewer) {
        courseService.assertCanAccessCourse(courseId, viewer);
        return posts.findAllForCourse(courseId).stream().map(GroupContentService::toPostDto).toList();
    }

    @Transactional
    public GroupPostDto createPost(UUID courseId, PlatformUser viewer, String title, String body, boolean pinned,
                                   List<AttachmentInput> attachments) {
        courseService.assertTeacherOwnsOrAdmin(courseId, viewer);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Тело поста не может быть пустым");
        }
        CourseEntity course = courseService.getCourse(courseId);
        UserEntity author = users.findById(viewer.id()).orElseThrow(() -> new IllegalArgumentException("Автор не найден"));

        GroupPostEntity post = new GroupPostEntity();
        post.setCourse(course);
        post.setAuthor(author);
        post.setTitle(title == null || title.isBlank() ? null : title.trim());
        post.setBody(body.trim());
        post.setPinned(pinned);

        if (attachments != null) {
            for (AttachmentInput a : attachments) {
                if (a == null || a.fileKey() == null || a.fileKey().isBlank()) {
                    continue;
                }
                GroupPostAttachmentEntity att = new GroupPostAttachmentEntity();
                att.setPost(post);
                att.setFileKey(a.fileKey());
                att.setOriginalName(a.originalName() == null ? "file" : a.originalName());
                att.setContentType(a.contentType());
                att.setSizeBytes(a.sizeBytes());
                post.getAttachments().add(att);
            }
        }
        return toPostDto(posts.save(post));
    }

    @Transactional
    public void deletePost(UUID postId, PlatformUser viewer) {
        GroupPostEntity post = posts.findById(postId).orElseThrow(() -> new IllegalArgumentException("Пост не найден"));
        courseService.assertTeacherOwnsOrAdmin(post.getCourse().getId(), viewer);
        posts.delete(post);
    }

    @Transactional(readOnly = true)
    public List<GroupMaterialDto> listMaterials(UUID courseId, PlatformUser viewer) {
        courseService.assertCanAccessCourse(courseId, viewer);
        return materials.findByCourse_IdOrderByUploadedAtDesc(courseId).stream()
                .map(GroupContentService::toMaterialDto)
                .toList();
    }

    @Transactional
    public GroupMaterialDto uploadMaterial(UUID courseId, PlatformUser viewer, MultipartFile file, String title, String description) {
        courseService.assertTeacherOwnsOrAdmin(courseId, viewer);
        CourseEntity course = courseService.getCourse(courseId);
        UserEntity uploader = users.findById(viewer.id()).orElseThrow(() -> new IllegalArgumentException("Загружающий не найден"));
        String key = files.save(file, "courses/" + courseId + "/materials");

        GroupMaterialEntity m = new GroupMaterialEntity();
        m.setCourse(course);
        m.setUploader(uploader);
        m.setFileKey(key);
        m.setOriginalName(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        m.setContentType(file.getContentType());
        m.setSizeBytes(file.getSize());
        m.setTitle(title == null || title.isBlank() ? null : title.trim());
        m.setDescription(description == null || description.isBlank() ? null : description.trim());
        m.setUploadedAt(OffsetDateTime.now());
        return toMaterialDto(materials.save(m));
    }

    @Transactional
    public void deleteMaterial(UUID materialId, PlatformUser viewer) {
        GroupMaterialEntity m = materials.findById(materialId).orElseThrow(() -> new IllegalArgumentException("Материал не найден"));
        courseService.assertTeacherOwnsOrAdmin(m.getCourse().getId(), viewer);
        materials.delete(m);
    }

    private static CourseDto toCourseDto(CourseEntity c) {
        UUID ownerId = c.getOwner() == null ? null : c.getOwner().getId();
        return new CourseDto(c.getId(), c.getTitle(), ownerId, c.getStudents().size(), c.getAvatarFileKey(), c.getDescription());
    }

    private static GroupPostDto toPostDto(GroupPostEntity p) {
        UserEntity author = p.getAuthor();
        String name = author == null ? "—" : Objects.toString(author.getFirstName(), "") + " " + Objects.toString(author.getLastName(), "");
        List<GroupPostDto.GroupPostAttachmentDto> atts = new ArrayList<>();
        for (GroupPostAttachmentEntity a : p.getAttachments()) {
            atts.add(new GroupPostDto.GroupPostAttachmentDto(a.getId(), a.getFileKey(), a.getOriginalName(), a.getContentType(), a.getSizeBytes()));
        }
        return new GroupPostDto(
                p.getId(),
                p.getCourse().getId(),
                author == null ? null : author.getId(),
                name.trim(),
                p.getTitle(),
                p.getBody(),
                p.isPinned(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                atts);
    }

    private static GroupMaterialDto toMaterialDto(GroupMaterialEntity m) {
        UserEntity u = m.getUploader();
        String name = u == null ? "—" : Objects.toString(u.getFirstName(), "") + " " + Objects.toString(u.getLastName(), "");
        return new GroupMaterialDto(
                m.getId(),
                m.getCourse().getId(),
                u == null ? null : u.getId(),
                name.trim(),
                m.getFileKey(),
                m.getOriginalName(),
                m.getContentType(),
                m.getSizeBytes(),
                m.getTitle(),
                m.getDescription(),
                m.getUploadedAt());
    }

    /**
     * Вход для прикреплённого файла поста: полагается, что файл уже загружен через {@code /api/v1/files/upload}.
     */
    public record AttachmentInput(String fileKey, String originalName, String contentType, Long sizeBytes) {
    }
}

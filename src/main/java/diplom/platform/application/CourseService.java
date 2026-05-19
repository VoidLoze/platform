package diplom.platform.application;

import diplom.platform.identity.domain.UserRole;
import diplom.platform.identity.infrastructure.UserEntity;
import diplom.platform.identity.infrastructure.UserJpaRepository;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.learning.infrastructure.CourseEntity;
import diplom.platform.learning.infrastructure.CourseJpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CourseService {
    private final CourseJpaRepository courses;
    private final UserJpaRepository users;
    private final ChatService chatService;

    public CourseService(CourseJpaRepository courses, UserJpaRepository users, ChatService chatService) {
        this.courses = courses;
        this.users = users;
        this.chatService = chatService;
    }

    public CourseEntity createCourse(String title, UserEntity owner) {
        CourseEntity course = new CourseEntity();
        course.setTitle(title);
        course.setOwner(owner);
        CourseEntity saved = courses.save(course);
        chatService.createCourseRoom(owner.getId(), saved.getId());
        return saved;
    }

    public java.util.List<CourseEntity> listCourses() {
        return courses.findAllCatalog();
    }

    public java.util.List<CourseEntity> listEnrolledForStudent(UUID studentId) {
        return courses.findEnrolledForStudent(studentId);
    }

    public java.util.List<CourseEntity> listMyCourses(UUID ownerId) {
        return courses.findTeacherCourses(ownerId);
    }

    public CourseEntity getCourse(UUID id) {
        return courses.findOneWithDetails(id).orElseThrow(() -> new IllegalArgumentException("Course not found"));
    }

    public CourseEntity getCourseForViewer(UUID id, PlatformUser viewer) {
        CourseEntity c = getCourse(id);
        if (viewer.role() == UserRole.ROLE_ADMIN) {
            return c;
        }
        if (viewer.role() == UserRole.ROLE_TEACHER) {
            if (c.getOwner() == null || !c.getOwner().getId().equals(viewer.id())) {
                throw new AccessDeniedException("Доступ только к своим группам");
            }
            return c;
        }
        if (viewer.role() == UserRole.ROLE_STUDENT) {
            boolean member = c.getStudents().stream().anyMatch(s -> s.getId().equals(viewer.id()));
            if (!member) {
                throw new AccessDeniedException("Вы не состоите в этой группе");
            }
            return c;
        }
        throw new AccessDeniedException("Forbidden");
    }

    public void assertCanAccessCourse(UUID courseId, PlatformUser viewer) {
        getCourseForViewer(courseId, viewer);
    }

    public List<UserEntity> getGroupRoster(UUID courseId, PlatformUser viewer) {
        assertTeacherOwnsOrAdmin(courseId, viewer);
        CourseEntity c = getCourse(courseId);
        return c.getStudents().stream().sorted((a, b) -> a.getLastName().compareToIgnoreCase(b.getLastName())).collect(Collectors.toList());
    }

    public void assertTeacherOwnsOrAdmin(UUID courseId, PlatformUser viewer) {
        if (viewer.role() == UserRole.ROLE_ADMIN) {
            return;
        }
        if (viewer.role() != UserRole.ROLE_TEACHER) {
            throw new AccessDeniedException("Forbidden");
        }
        CourseEntity c = getCourse(courseId);
        if (c.getOwner() == null || !c.getOwner().getId().equals(viewer.id())) {
            throw new AccessDeniedException("Только владелец группы");
        }
    }

    public CourseEntity enrollStudent(UUID courseId, UUID studentId, UUID actorId) {
        CourseEntity course = courses.findOneWithDetails(courseId).orElseThrow(() -> new IllegalArgumentException("Группа не найдена"));
        UserEntity student = users.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        UserEntity actor = users.findById(actorId).orElseThrow(() -> new IllegalArgumentException("Actor not found"));

        if (student.getRole() != UserRole.ROLE_STUDENT) {
            throw new IllegalArgumentException("В группу можно добавлять только пользователей со ролью студента");
        }

        if (actor.getRole() == UserRole.ROLE_ADMIN) {
            course.getStudents().add(student);
            return courses.save(course);
        }
        if (actor.getRole() == UserRole.ROLE_TEACHER) {
            if (course.getOwner() == null || !course.getOwner().getId().equals(actor.getId())) {
                throw new AccessDeniedException("Только владелец группы может добавлять студентов");
            }
            course.getStudents().add(student);
            return courses.save(course);
        }
        throw new AccessDeniedException("Только преподаватель или администратор может назначать студентов");
    }
}

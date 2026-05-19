package diplom.platform.application;

import diplom.platform.infrastructure.storage.FileStorageService;
import diplom.platform.learning.infrastructure.AssignmentEntity;
import diplom.platform.learning.infrastructure.AssignmentJpaRepository;
import diplom.platform.learning.infrastructure.AssignmentMaterialEntity;
import diplom.platform.learning.infrastructure.AssignmentMaterialJpaRepository;
import diplom.platform.ui.dto.AssignmentMaterialDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
public class AssignmentMaterialService {
    private final AssignmentJpaRepository assignments;
    private final AssignmentMaterialJpaRepository materials;
    private final FileStorageService files;

    public AssignmentMaterialService(AssignmentJpaRepository assignments, AssignmentMaterialJpaRepository materials, FileStorageService files) {
        this.assignments = assignments;
        this.materials = materials;
        this.files = files;
    }

    @Transactional
    public AssignmentMaterialDto upload(UUID assignmentId, MultipartFile file) {
        AssignmentEntity assignment = assignments.findById(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        UUID courseId = assignment.getCourse().getId();
        String key = files.save(file, "courses/" + courseId + "/assignments/" + assignmentId + "/materials");
        AssignmentMaterialEntity material = new AssignmentMaterialEntity();
        material.setAssignment(assignment);
        material.setFileKey(key);
        material.setOriginalName(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        material.setContentType(file.getContentType());
        material = materials.save(material);
        return toDto(material);
    }

    @Transactional(readOnly = true)
    public List<AssignmentMaterialDto> list(UUID assignmentId) {
        return materials.findByAssignment_IdOrderByUploadedAtDesc(assignmentId).stream().map(AssignmentMaterialService::toDto).toList();
    }

    private static AssignmentMaterialDto toDto(AssignmentMaterialEntity material) {
        return new AssignmentMaterialDto(
                material.getId(),
                material.getFileKey(),
                material.getOriginalName(),
                material.getContentType(),
                material.getUploadedAt()
        );
    }
}

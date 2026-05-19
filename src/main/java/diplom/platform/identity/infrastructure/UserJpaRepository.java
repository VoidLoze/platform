package diplom.platform.identity.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            SELECT u FROM UserEntity u WHERE u.id <> :excludeId
            AND (
              LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :q, '%'))
            )
            """)
    List<UserEntity> searchByName(@Param("q") String q, @Param("excludeId") UUID excludeId, Pageable pageable);
}

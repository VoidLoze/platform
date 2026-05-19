package diplom.platform.communication.infrastructure;

import diplom.platform.communication.domain.ChatRoomType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRoomJpaRepository extends JpaRepository<ChatRoomEntity, UUID> {
    @EntityGraph(attributePaths = {"course", "createdBy"})
    @Query("""
            select distinct r from ChatRoomEntity r
            join ChatParticipantEntity p on p.room.id = r.id
            where p.user.id = :userId
            order by r.createdAt desc
            """)
    List<ChatRoomEntity> findForUser(@Param("userId") UUID userId);

    @EntityGraph(attributePaths = {"course", "createdBy"})
    Optional<ChatRoomEntity> findById(UUID id);

    @Query("""
            select r from ChatRoomEntity r
            where r.roomType = :directType
              and (select count(p) from ChatParticipantEntity p where p.room.id = r.id) = 2
              and exists (select 1 from ChatParticipantEntity p1 where p1.room.id = r.id and p1.user.id = :u1)
              and exists (select 1 from ChatParticipantEntity p2 where p2.room.id = r.id and p2.user.id = :u2)
            """)
    Optional<ChatRoomEntity> findDirectRoomBetweenUsers(
            @Param("u1") UUID userId1,
            @Param("u2") UUID userId2,
            @Param("directType") ChatRoomType directType);
}

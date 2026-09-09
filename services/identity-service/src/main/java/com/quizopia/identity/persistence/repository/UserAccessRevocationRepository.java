package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.UserAccessRevocationEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserAccessRevocationRepository extends JpaRepository<UserAccessRevocationEntity, UUID> {
    @Modifying
    @Transactional
    @Query(
            value =
                    """
                    INSERT INTO user_access_revocation (user_id, revoked_before)
                    VALUES (:userId, :revokedBefore)
                    ON CONFLICT (user_id) DO UPDATE
                    SET revoked_before = GREATEST(
                            user_access_revocation.revoked_before,
                            EXCLUDED.revoked_before)
                    """,
            nativeQuery = true)
    int upsertRevokedBefore(@Param("userId") UUID userId, @Param("revokedBefore") Instant revokedBefore);
}

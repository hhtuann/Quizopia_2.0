package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.EmailVerificationChallengeEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallengeEntity, UUID> {
    /** The caller must already hold the owning user row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from EmailVerificationChallengeEntity challenge where challenge.userId = :userId")
    Optional<EmailVerificationChallengeEntity> findByUserIdForUpdate(@Param("userId") UUID userId);
}

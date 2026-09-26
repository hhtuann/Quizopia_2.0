package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.UserAccountEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccountEntity user where user.id = :userId")
    Optional<UserAccountEntity> findByIdForUpdate(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccountEntity user where user.email = :email order by user.id")
    List<UserAccountEntity> findAllByEmailForLinking(@Param("email") String email);
}

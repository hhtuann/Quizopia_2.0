package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.RefreshTokenFamilyEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamilyEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select family from RefreshTokenFamilyEntity family where family.id = :familyId")
    Optional<RefreshTokenFamilyEntity> findByIdForUpdate(@Param("familyId") UUID familyId);
}

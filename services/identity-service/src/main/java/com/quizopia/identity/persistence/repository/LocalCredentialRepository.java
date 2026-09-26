package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalCredentialRepository extends JpaRepository<LocalCredentialEntity, UUID> {
    Optional<LocalCredentialEntity> findByUserId(UUID userId);
}

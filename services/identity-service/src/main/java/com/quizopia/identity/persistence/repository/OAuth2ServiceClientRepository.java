package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.OAuth2ServiceClientEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OAuth2ServiceClientRepository extends JpaRepository<OAuth2ServiceClientEntity, UUID> {
    @EntityGraph(attributePaths = "scopes")
    @Override
    Optional<OAuth2ServiceClientEntity> findById(UUID id);

    @EntityGraph(attributePaths = "scopes")
    Optional<OAuth2ServiceClientEntity> findByClientId(String clientId);

    @Modifying
    @Transactional
    @Query("update OAuth2ServiceClientEntity c "
            + "set c.clientSecretHash = :clientSecretHash "
            + "where c.id = :id "
            + "and c.clientSecretHash = :expectedClientSecretHash "
            + "and c.enabled = true")
    int updateClientSecretHash(
            @Param("id") UUID id,
            @Param("expectedClientSecretHash") String expectedClientSecretHash,
            @Param("clientSecretHash") String clientSecretHash);
}

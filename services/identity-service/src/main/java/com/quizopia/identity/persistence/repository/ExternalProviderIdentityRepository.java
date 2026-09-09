package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.ExternalProviderIdentityEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ExternalProviderIdentityRepository extends JpaRepository<ExternalProviderIdentityEntity, UUID> {
    Optional<ExternalProviderIdentityEntity> findByProviderAndProviderSubject(String provider, String providerSubject);

    @Query("select identity from ExternalProviderIdentityEntity identity "
            + "where identity.user.id = :userId and identity.provider = :provider")
    List<ExternalProviderIdentityEntity> findAllByUserIdAndProvider(
            @Param("userId") UUID userId, @Param("provider") String provider);

    @Modifying
    @Transactional
    @Query(
            value = "INSERT INTO external_provider_identity "
                    + "(id, user_id, provider, provider_subject, created_at, updated_at) "
                    + "VALUES (:id, :userId, :provider, :providerSubject, :createdAt, :updatedAt) "
                    + "ON CONFLICT (provider, provider_subject) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);
}

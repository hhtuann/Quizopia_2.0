package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    Optional<RefreshTokenEntity> findByTokenHash(byte[] tokenHash);

    @Query("select token from RefreshTokenEntity token join fetch token.family where token.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashWithFamily(@Param("tokenHash") byte[] tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update RefreshTokenEntity token "
            + "set token.consumedAt = :consumedAt "
            + "where token.id = :tokenId and token.consumedAt is null")
    int consumeIfUnused(@Param("tokenId") UUID tokenId, @Param("consumedAt") Instant consumedAt);
}

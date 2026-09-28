package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.currentuser.CurrentUserService;
import com.quizopia.identity.application.logout.CurrentSessionLogoutService;
import com.quizopia.identity.application.refresh.RefreshRotationStatus;
import com.quizopia.identity.application.refresh.RefreshSessionService;
import com.quizopia.identity.persistence.entity.UserAccessRevocationEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.UserAccessRevocationRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class CurrentIdentitySessionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private CurrentSessionLogoutService logoutService;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private RefreshSessionService refreshSessionService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private UserAccessRevocationRepository revocationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(name = "identityClock")
    private Clock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM refresh_token_family");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM user_access_revocation");
        jdbc.update("DELETE FROM user_account");
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void activeCredentialLogoutRevokesOnlyItsFamilyWithoutConsumingOrCreatingTokens() {
        UserAccountEntity user = persistEligibleUser("logout-active");
        var issuance = refreshSessionService.issueInitial(user.getId(), NOW.plus(Duration.ofDays(7)));

        logoutService.logout(issuance.credential());

        assertEquals(NOW, instant("SELECT revoked_at FROM refresh_token_family WHERE id = ?", issuance.familyId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token_family"));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(0L, count("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL"));
        assertEquals(
                RefreshRotationStatus.REVOKED_FAMILY,
                refreshSessionService
                        .rotate(issuance.credential(), NOW.plusSeconds(1))
                        .status());
    }

    @Test
    void unknownExpiredAndAlreadyRevokedCredentialsAreSafeIdempotentOutcomes() {
        UserAccountEntity user = persistEligibleUser("logout-idempotent");
        var issuance = refreshSessionService.issueInitial(user.getId(), NOW.plus(Duration.ofDays(1)));

        logoutService.logout(com.quizopia.identity.security.refresh.RawRefreshCredential.from(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertNull(instant("SELECT revoked_at FROM refresh_token_family WHERE id = ?", issuance.familyId()));

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        logoutService.logout(issuance.credential());
        assertNull(instant("SELECT revoked_at FROM refresh_token_family WHERE id = ?", issuance.familyId()));

        jdbc.update(
                "UPDATE refresh_token_family SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW),
                issuance.familyId());
        logoutService.logout(issuance.credential());
        assertEquals(NOW, instant("SELECT revoked_at FROM refresh_token_family WHERE id = ?", issuance.familyId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM refresh_token"));
    }

    @Test
    void consumedCredentialLogoutRevokesFamilyAndInvalidatesItsReplacement() {
        UserAccountEntity user = persistEligibleUser("logout-reuse");
        var issuance = refreshSessionService.issueInitial(user.getId(), NOW.plus(Duration.ofDays(7)));
        var rotation = refreshSessionService.rotate(issuance.credential(), NOW.plusSeconds(1));
        var replacement = rotation.replacementCredential().orElseThrow();
        Instant logoutTime = NOW.plus(Duration.ofDays(8));
        when(clock.instant()).thenReturn(logoutTime);

        logoutService.logout(issuance.credential());

        assertEquals(
                logoutTime, instant("SELECT revoked_at FROM refresh_token_family WHERE id = ?", issuance.familyId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM refresh_token"));
        assertEquals(
                RefreshRotationStatus.REVOKED_FAMILY,
                refreshSessionService.rotate(replacement, NOW.plusSeconds(3)).status());
    }

    @Test
    void currentUserReloadsAuthoritativeRolesAndAppliesInclusiveRevocationCutoff() {
        UserAccountEntity user = persistEligibleUser("current-profile");
        Instant issuedAt = NOW.minusSeconds(30);
        userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.TEACHER));

        var profile = currentUserService.findEligible(user.getId(), issuedAt).orElseThrow();

        assertEquals(user.getId(), profile.id());
        assertEquals(user.getUsername(), profile.username());
        assertEquals(user.getEmail(), profile.email());
        assertEquals(List.of("STUDENT", "TEACHER"), profile.roles());

        revocationRepository.saveAndFlush(new UserAccessRevocationEntity(user.getId(), issuedAt));
        assertTrue(currentUserService.findEligible(user.getId(), issuedAt).isEmpty());
        assertTrue(currentUserService
                .findEligible(user.getId(), issuedAt.minusNanos(1))
                .isEmpty());
        assertTrue(currentUserService
                .findEligible(user.getId(), issuedAt.plusNanos(1))
                .isPresent());
    }

    @Test
    void currentUserFailsClosedForMissingDisabledUnverifiedOrNonStudentAccounts() {
        assertTrue(currentUserService.findEligible(UUID.randomUUID(), NOW).isEmpty());

        UserAccountEntity disabled = persistEligibleUser("current-disabled");
        disabled.setAccountStatus("DISABLED");
        userAccountRepository.saveAndFlush(disabled);
        assertTrue(currentUserService.findEligible(disabled.getId(), NOW).isEmpty());

        UserAccountEntity unverified = persistEligibleUser("current-unverified");
        unverified.setEmailVerifiedAt(null);
        userAccountRepository.saveAndFlush(unverified);
        assertTrue(currentUserService.findEligible(unverified.getId(), NOW).isEmpty());

        UserAccountEntity noStudent = persistEligibleUser("current-no-student");
        userRoleRepository.deleteAll(userRoleRepository.findAllByUser_Id(noStudent.getId()));
        userRoleRepository.flush();
        assertTrue(currentUserService.findEligible(noStudent.getId(), NOW).isEmpty());
    }

    private UserAccountEntity persistEligibleUser(String prefix) {
        UserAccountEntity user = new UserAccountEntity(prefix + "@gmail.com", prefix);
        user.setAccountStatus(AccountLifecycleStatus.ACTIVE);
        user.setEmailVerifiedAt(NOW.minusSeconds(60));
        user = userAccountRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        return user;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private Instant instant(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Instant.class, arguments);
    }
}

package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationInput;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationResult;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationService;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationStatus;
import com.quizopia.identity.persistence.entity.LocalCredentialEntity;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.LocalCredentialRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class LocalAuthenticationIntegrationTest {
    private static final Instant VERIFIED_AT = Instant.parse("2026-09-22T00:00:00Z");

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
    private LocalAuthenticationService authenticationService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private LocalCredentialRepository localCredentialRepository;

    @Autowired
    @Qualifier("serviceClientPasswordEncoder") private PasswordEncoder passwordEncoder;

    @Test
    void exactUsernameAndVerifiedEmailAuthenticateTheSameEligibleUser() {
        String password = "local-auth-secret-" + UUID.randomUUID();
        UserAccountEntity user = persistUser(
                "Exact-Username-" + UUID.randomUUID(),
                "Exact.Email-" + UUID.randomUUID() + "@example.com",
                password,
                AccountLifecycleStatus.ACTIVE,
                VERIFIED_AT,
                true);

        assertAuthenticated(user, user.getUsername(), password);
        assertAuthenticated(user, user.getEmail(), password);
        assertFailed(authenticate(user.getUsername().toLowerCase(java.util.Locale.ROOT), password));
        assertFailed(authenticate(user.getEmail().toLowerCase(java.util.Locale.ROOT), password));
    }

    @Test
    void unknownWrongPasswordAndIneligibleAccountsExposeOneFailureResult() {
        String password = "shared-failure-secret-" + UUID.randomUUID();
        UserAccountEntity eligible = persistEligibleUser("eligible", password);
        UserAccountEntity unverified = persistUser(
                "unverified-" + UUID.randomUUID(),
                "unverified-" + UUID.randomUUID() + "@example.com",
                password,
                AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION,
                null,
                false);
        UserAccountEntity disabled = persistUser(
                "disabled-" + UUID.randomUUID(),
                "disabled-" + UUID.randomUUID() + "@example.com",
                password,
                "DISABLED",
                VERIFIED_AT,
                true);
        UserAccountEntity roleless = persistUser(
                "roleless-" + UUID.randomUUID(),
                "roleless-" + UUID.randomUUID() + "@example.com",
                password,
                AccountLifecycleStatus.ACTIVE,
                VERIFIED_AT,
                false);

        LocalAuthenticationResult expectedFailure = LocalAuthenticationResult.failed();
        assertEquals(expectedFailure, authenticate("unknown-" + UUID.randomUUID(), password));
        assertEquals(expectedFailure, authenticate(eligible.getUsername(), "wrong-" + password));
        assertEquals(expectedFailure, authenticate(unverified.getUsername(), password));
        assertEquals(expectedFailure, authenticate(disabled.getUsername(), password));
        assertEquals(expectedFailure, authenticate(roleless.getUsername(), password));
    }

    @Test
    void identifierMatchingDifferentUsernameAndEmailOwnersFailsGenerically() {
        String identifier = "namespace-collision-" + UUID.randomUUID() + "@example.com";
        String sharedPassword = "collision-secret-" + UUID.randomUUID();
        persistUser(
                identifier,
                "username-owner-" + UUID.randomUUID() + "@example.com",
                sharedPassword,
                AccountLifecycleStatus.ACTIVE,
                VERIFIED_AT,
                true);
        persistUser(
                "email-owner-" + UUID.randomUUID(),
                identifier,
                sharedPassword,
                AccountLifecycleStatus.ACTIVE,
                VERIFIED_AT,
                true);

        assertFailed(authenticate(identifier, sharedPassword));
    }

    @Test
    void sameAccountUsernameAndVerifiedEmailResolutionIsOneIdentity() {
        String identifier = "dual-resolution-" + UUID.randomUUID() + "@example.com";
        String password = "dual-resolution-secret-" + UUID.randomUUID();
        UserAccountEntity user =
                persistUser(identifier, identifier, password, AccountLifecycleStatus.ACTIVE, VERIFIED_AT, true);

        assertAuthenticated(user, identifier, password);
    }

    @Test
    void inputPreservesIdentifierAndRedactsPasswordDiagnostics() {
        String identifier = " Exact.Identifier@example.com ";
        String password = "diagnostic-secret-" + UUID.randomUUID();
        LocalAuthenticationInput input = new LocalAuthenticationInput(identifier, RawLocalPassword.from(password));

        assertEquals(identifier, input.identifier());
        assertFalse(input.toString().contains(identifier));
        assertFalse(input.toString().contains(password));
    }

    private UserAccountEntity persistEligibleUser(String prefix, String password) {
        return persistUser(
                prefix + "-" + UUID.randomUUID(),
                prefix + "-" + UUID.randomUUID() + "@example.com",
                password,
                AccountLifecycleStatus.ACTIVE,
                VERIFIED_AT,
                true);
    }

    private UserAccountEntity persistUser(
            String username, String email, String password, String status, Instant verifiedAt, boolean student) {
        UserAccountEntity user = new UserAccountEntity(email, username);
        user.setAccountStatus(status);
        user.setEmailVerifiedAt(verifiedAt);
        user = userAccountRepository.saveAndFlush(user);
        localCredentialRepository.saveAndFlush(new LocalCredentialEntity(user, passwordEncoder.encode(password)));
        if (student) {
            userRoleRepository.saveAndFlush(new UserRoleEntity(user, UserRole.STUDENT));
        }
        return user;
    }

    private LocalAuthenticationResult authenticate(String identifier, String password) {
        return authenticationService.authenticate(
                new LocalAuthenticationInput(identifier, RawLocalPassword.from(password)));
    }

    private void assertAuthenticated(UserAccountEntity expectedUser, String identifier, String password) {
        LocalAuthenticationResult result = authenticate(identifier, password);
        assertEquals(LocalAuthenticationStatus.AUTHENTICATED, result.status());
        assertEquals(expectedUser.getId(), result.authenticatedUserId().orElseThrow());
    }

    private void assertFailed(LocalAuthenticationResult result) {
        assertEquals(LocalAuthenticationStatus.AUTHENTICATION_FAILED, result.status());
        assertTrue(result.authenticatedUserId().isEmpty());
    }
}

package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.application.teacherenablement.TeacherEnablementService;
import com.quizopia.identity.application.teacherenablement.TeacherEnablementStatus;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.entity.UserRole;
import com.quizopia.identity.persistence.entity.UserRoleEntity;
import com.quizopia.identity.persistence.repository.RoleGrantAuditRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import com.quizopia.identity.persistence.repository.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
class TeacherEnablementIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T08:30:00Z");

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
    private TeacherEnablementService teacherEnablementService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleGrantAuditRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @MockitoBean(name = "identityClock")
    private Clock clock;

    @BeforeEach
    void resetState() {
        dropFailureTrigger();
        jdbc.update("DELETE FROM role_grant_audit");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM user_account");
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_teacher_audit_insert ON role_grant_audit");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_teacher_audit_insert()");
    }

    @Test
    void activeVerifiedStudentReceivesAdditiveTeacherRoleAndDurableFirstGrantAudit() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.STUDENT);

        assertEquals(TeacherEnablementStatus.ENABLED, teacherEnablementService.enable(user.getId()));

        assertEquals(List.of("STUDENT", "TEACHER"), roleNames(user.getId()));
        assertEquals(1L, teacherRoleCount(user.getId()));
        assertEquals(1L, auditRepository.count());
        assertEquals(user.getId(), jdbc.queryForObject("SELECT actor_user_id FROM role_grant_audit", UUID.class));
        assertEquals(user.getId(), jdbc.queryForObject("SELECT target_user_id FROM role_grant_audit", UUID.class));
        assertEquals("TEACHER", jdbc.queryForObject("SELECT role FROM role_grant_audit", String.class));
        assertEquals("ROLE_GRANTED", jdbc.queryForObject("SELECT action FROM role_grant_audit", String.class));
        assertEquals("SELF_SERVICE", jdbc.queryForObject("SELECT source FROM role_grant_audit", String.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT occurred_at FROM role_grant_audit", Instant.class));
    }

    @Test
    void alreadyTeacherIsSuccessfulNoOpWithoutAdditionalRoleOrAudit() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.STUDENT);

        assertEquals(TeacherEnablementStatus.ENABLED, teacherEnablementService.enable(user.getId()));
        assertEquals(TeacherEnablementStatus.ALREADY_ENABLED, teacherEnablementService.enable(user.getId()));

        assertEquals(1L, teacherRoleCount(user.getId()));
        assertEquals(1L, auditRepository.count());
        assertEquals(List.of("STUDENT", "TEACHER"), roleNames(user.getId()));
    }

    @Test
    void activeVerifiedStudentAndAdminReceivesTeacherWhilePreservingBothRoles() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.STUDENT, UserRole.ADMIN);

        assertEquals(TeacherEnablementStatus.ENABLED, teacherEnablementService.enable(user.getId()));

        assertEquals(List.of("ADMIN", "STUDENT", "TEACHER"), roleNames(user.getId()));
        assertEquals(1L, auditRepository.count());
    }

    @Test
    void activeVerifiedAdminOnlyUserIsIneligibleWithoutMutationOrAudit() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.ADMIN);

        assertEquals(TeacherEnablementStatus.INELIGIBLE, teacherEnablementService.enable(user.getId()));

        assertEquals(List.of("ADMIN"), roleNames(user.getId()));
        assertEquals(0L, teacherRoleCount(user.getId()));
        assertEquals(0L, auditRepository.count());
    }

    @Test
    void activeVerifiedUserWithoutStudentIsIneligibleWithoutMutationOrAudit() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true);

        assertEquals(TeacherEnablementStatus.INELIGIBLE, teacherEnablementService.enable(user.getId()));

        assertEquals(List.of(), roleNames(user.getId()));
        assertEquals(0L, teacherRoleCount(user.getId()));
        assertEquals(0L, auditRepository.count());
    }

    @Test
    void ineligibleAndInconsistentAccountsFailClosedWithoutMutationOrAudit() {
        List<UserAccountEntity> ineligible = List.of(
                persistAccount(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, false, UserRole.STUDENT),
                persistAccount(AccountLifecycleStatus.PENDING_EMAIL_VERIFICATION, true, UserRole.STUDENT),
                persistAccount(AccountLifecycleStatus.ACTIVE, false, UserRole.STUDENT),
                persistAccount("DISABLED", true, UserRole.STUDENT));

        for (UserAccountEntity user : ineligible) {
            assertEquals(TeacherEnablementStatus.INELIGIBLE, teacherEnablementService.enable(user.getId()));
            assertEquals(0L, teacherRoleCount(user.getId()));
        }
        assertEquals(TeacherEnablementStatus.INELIGIBLE, teacherEnablementService.enable(UUID.randomUUID()));
        assertEquals(0L, auditRepository.count());
    }

    @Test
    void auditFailureRollsBackTeacherRoleInTheSameTransaction() {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.STUDENT);
        jdbc.execute(
                """
                CREATE FUNCTION fail_teacher_audit_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced role grant audit failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute(
                """
                CREATE TRIGGER fail_teacher_audit_insert
                BEFORE INSERT ON role_grant_audit
                FOR EACH ROW EXECUTE FUNCTION fail_teacher_audit_insert()
                """);

        assertThrows(RuntimeException.class, () -> teacherEnablementService.enable(user.getId()));

        assertEquals(List.of("STUDENT"), roleNames(user.getId()));
        assertEquals(0L, teacherRoleCount(user.getId()));
        assertEquals(0L, auditRepository.count());
    }

    @Test
    void concurrentEnablementConvergesToOneRoleAndOneFirstGrantAudit() throws Exception {
        UserAccountEntity user = persistAccount(AccountLifecycleStatus.ACTIVE, true, UserRole.STUDENT);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<TeacherEnablementStatus> first = executor.submit(() -> enableAfter(start, user.getId()));
            Future<TeacherEnablementStatus> second = executor.submit(() -> enableAfter(start, user.getId()));
            start.countDown();

            Set<TeacherEnablementStatus> statuses =
                    Set.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(Set.of(TeacherEnablementStatus.ENABLED, TeacherEnablementStatus.ALREADY_ENABLED), statuses);
            assertEquals(1L, teacherRoleCount(user.getId()));
            assertEquals(1L, auditRepository.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cleanV12MigrationAndHibernateValidationExposeOnlyMinimalAuditFields() {
        assertEquals("12", flyway.info().current().getVersion().getVersion());
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name = 'role_grant_audit'",
                        Long.class));
        Set<String> columns = Set.copyOf(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'role_grant_audit'",
                String.class));
        assertEquals(
                Set.of("id", "actor_user_id", "target_user_id", "role", "action", "source", "occurred_at"), columns);
        assertTrue(columns.stream().noneMatch(column -> column.matches(".*(token|password|otp|credential).*")));
    }

    private TeacherEnablementStatus enableAfter(CountDownLatch start, UUID userId) throws Exception {
        assertTrue(start.await(30, TimeUnit.SECONDS));
        return teacherEnablementService.enable(userId);
    }

    private UserAccountEntity persistAccount(String status, boolean verified, UserRole... roles) {
        String suffix = UUID.randomUUID().toString();
        UserAccountEntity user = new UserAccountEntity("teacher-" + suffix + "@gmail.com", "teacher-" + suffix);
        user.setAccountStatus(status);
        user.setEmailVerifiedAt(verified ? NOW.minusSeconds(60) : null);
        user = userAccountRepository.saveAndFlush(user);
        UserAccountEntity persisted = user;
        Arrays.stream(roles).forEach(role -> userRoleRepository.saveAndFlush(new UserRoleEntity(persisted, role)));
        return user;
    }

    private List<String> roleNames(UUID userId) {
        return userRoleRepository.findAllByUser_Id(userId).stream()
                .map(role -> role.getRole().name())
                .sorted()
                .toList();
    }

    private long teacherRoleCount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role = 'TEACHER'", Long.class, userId);
    }
}

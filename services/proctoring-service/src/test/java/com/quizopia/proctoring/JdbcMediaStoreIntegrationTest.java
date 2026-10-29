package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.Session;
import com.quizopia.proctoring.media.*;
import com.quizopia.proctoring.media.persistence.JdbcMediaStore;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real PostgreSQL grant lifecycle and recovery; no provider qualification or production activation. */
@Testcontainers
class JdbcMediaStoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    static DriverManagerDataSource source;
    static JdbcTemplate db;
    JdbcMediaStore store;
    JdbcMonitoringStore monitoring;
    UUID student;
    UUID teacher;
    UUID attempt;
    UUID publication;
    UUID classroom;
    Instant deadline;
    Session session;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void fixture() {
        db.execute(
                "TRUNCATE monitoring_media_grants,monitoring_media_rooms,monitoring_access_audit,monitoring_evidence,monitoring_sessions RESTART IDENTITY CASCADE");
        store = new JdbcMediaStore(db, new DataSourceTransactionManager(source));
        monitoring = new JdbcMonitoringStore(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        attempt = UUID.randomUUID();
        publication = UUID.randomUUID();
        classroom = UUID.randomUUID();
        deadline = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        session = monitoring.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
    }

    MonitoringAuthority.Actor actor(UUID id, String role) {
        return new MonitoringAuthority.Actor(
                id,
                Set.of(role),
                "synthetic-user-evidence-never-persist",
                Instant.now().plusSeconds(600));
    }

    MonitoringAuthority.Snapshot authority(UUID id, String role, boolean active) {
        return new MonitoringAuthority.Snapshot(
                id, role, attempt, publication, classroom, student, active, deadline, Instant.now());
    }

    MediaStore.Lease prepare(UUID id, String role) {
        return store.prepare(actor(id, role), authority(id, role, true), session.id(), Duration.ofSeconds(10));
    }

    MediaStore.Lease active() {
        var grant = prepare(student, "STUDENT");
        return store.activate(actor(student, "STUDENT"), authority(student, "STUDENT", true), grant.id());
    }

    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    void failure(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(MonitoringFailure.class, action);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }

    @Test
    void freshFlywayAppliesAdditiveV2AndStoresNoUserBearerColumns() {
        assertEquals(
                List.of("1", "2", "3", "4", "5"),
                db.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class));
        var columns = db.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name IN ('monitoring_media_rooms','monitoring_media_grants')",
                String.class);
        assertFalse(columns.stream()
                .anyMatch(name -> name.contains("token") || name.contains("jwt") || name.contains("evidence")));
    }

    @Test
    void repeatedAndConcurrentPreparationPreservesOneOpaqueRoomAndGrant() throws Exception {
        var first = prepare(student, "STUDENT");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<MediaStore.Lease> action = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return prepare(student, "STUDENT");
            };
            var a = executor.submit(action);
            var b = executor.submit(action);
            assertEquals(first, a.get(10, TimeUnit.SECONDS));
            assertEquals(first, b.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("monitoring_media_rooms"));
        assertEquals(1, count("monitoring_media_grants"));
        assertFalse(first.room().contains(student.toString()));
        assertFalse(first.room().contains(publication.toString()));
        assertFalse(first.identity().contains(student.toString()));
    }

    @Test
    void teacherAndStudentHaveDistinctIdentityAndRoleInSameSessionRoom() {
        var studentGrant = prepare(student, "STUDENT");
        var teacherGrant = prepare(teacher, "TEACHER");
        assertEquals(studentGrant.room(), teacherGrant.room());
        assertNotEquals(studentGrant.identity(), teacherGrant.identity());
        assertEquals("STUDENT", studentGrant.role());
        assertEquals("TEACHER", teacherGrant.role());
        assertEquals(2, count("monitoring_media_grants"));
    }

    @Test
    void prepareAndRenewAreBoundedByCurrentUserExpiry() {
        var shorter = new MonitoringAuthority.Actor(
                student,
                Set.of("STUDENT"),
                "synthetic",
                Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.MICROS));
        var prepared =
                store.prepare(shorter, authority(student, "STUDENT", true), session.id(), Duration.ofSeconds(30));
        assertFalse(prepared.leaseUntil().isAfter(shorter.expiresAt()));
        store.activate(shorter, authority(student, "STUDENT", true), prepared.id());
        var renewed = store.renew(shorter, authority(student, "STUDENT", true), prepared.id(), Duration.ofSeconds(30));
        assertFalse(renewed.leaseUntil().isAfter(shorter.expiresAt()));
        assertFalse(renewed.leaseUntil().isAfter(deadline));
    }

    @Test
    void reusedPreparationCannotIssueLeaseBeyondNewCallerExpiry() {
        var original = prepare(student, "STUDENT");
        var shorter = new MonitoringAuthority.Actor(
                student,
                Set.of("STUDENT"),
                "synthetic-new-user",
                Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MICROS));
        var reused = store.prepare(shorter, authority(student, "STUDENT", true), session.id(), Duration.ofSeconds(10));
        assertFalse(reused.leaseUntil().isAfter(shorter.expiresAt()));
        assertEquals(original.id(), reused.id());
    }

    @Test
    void staleWrongOwnerOrInactiveAuthorityCannotPrepareGrant() {
        var a = authority(student, "STUDENT", true);
        var stale = new MonitoringAuthority.Snapshot(
                student,
                "STUDENT",
                attempt,
                publication,
                classroom,
                student,
                true,
                deadline,
                Instant.now().minusSeconds(10));
        failure(
                503,
                "AUTHORITY_UNAVAILABLE",
                () -> store.prepare(actor(student, "STUDENT"), stale, session.id(), Duration.ofSeconds(10)));
        failure(
                503,
                "AUTHORITY_UNAVAILABLE",
                () -> store.prepare(
                        actor(teacher, "STUDENT"),
                        authority(teacher, "STUDENT", true),
                        session.id(),
                        Duration.ofSeconds(10)));
        failure(
                409,
                "SESSION_ENDED",
                () -> store.prepare(
                        actor(student, "STUDENT"),
                        authority(student, "STUDENT", false),
                        session.id(),
                        Duration.ofSeconds(10)));
        assertEquals(0, count("monitoring_media_rooms"));
        assertEquals(0, count("monitoring_media_grants"));
    }

    @Test
    void activationAndRenewalCannotBePerformedByAnotherActor() {
        var prepared = prepare(student, "STUDENT");
        failure(
                404,
                "PROCTORING_NOT_ACCESSIBLE",
                () -> store.activate(actor(teacher, "TEACHER"), authority(teacher, "TEACHER", true), prepared.id()));
        var active = store.activate(actor(student, "STUDENT"), authority(student, "STUDENT", true), prepared.id());
        assertEquals("ACTIVE", active.status());
        assertEquals(
                active, store.activate(actor(student, "STUDENT"), authority(student, "STUDENT", true), prepared.id()));
        failure(
                404,
                "PROCTORING_NOT_ACCESSIBLE",
                () -> store.renew(
                        actor(teacher, "TEACHER"),
                        authority(teacher, "TEACHER", true),
                        prepared.id(),
                        Duration.ofSeconds(10)));
    }

    @Test
    void explicitFenceIsDurableAndTerminalStatesCannotBeReactivated() {
        var grant = active();
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> store.fence(grant.id(), teacher));
        assertEquals("ACTIVE", store.fence(grant.id(), null).status());
        assertEquals("REVOKE_PENDING", store.fence(grant.id(), student).status());
        var reconstructed = new JdbcMediaStore(db, new DataSourceTransactionManager(source));
        assertEquals(grant.id(), reconstructed.pending(1).getFirst().id());
        failure(
                409,
                "MEDIA_GRANT_ENDED",
                () -> store.activate(actor(student, "STUDENT"), authority(student, "STUDENT", true), grant.id()));
        failure(
                409,
                "MEDIA_GRANT_ENDED",
                () -> store.renew(
                        actor(student, "STUDENT"),
                        authority(student, "STUDENT", true),
                        grant.id(),
                        Duration.ofSeconds(10)));
        store.revoked(grant.id());
        store.revoked(grant.id());
        assertEquals("REVOKED", store.find(grant.id()).status());
        assertTrue(store.pending(50).isEmpty());
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.execute("UPDATE monitoring_media_grants SET state='ACTIVE',revoked_at=NULL"));
    }

    @Test
    void expiredLeaseBecomesRecoveryCandidateAndRequiresProviderRevocationAcknowledgment() throws Exception {
        var grant = store.prepare(
                actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), Duration.ofSeconds(1));
        store.activate(actor(student, "STUDENT"), authority(student, "STUDENT", true), grant.id());
        while (Instant.now().isBefore(grant.leaseUntil().plusMillis(20))) Thread.sleep(5);
        assertEquals(grant.id(), store.pending(10).getFirst().id());
        assertEquals("REVOKE_PENDING", store.fence(grant.id(), null).status());
        assertEquals(
                "REVOKE_PENDING",
                new JdbcMediaStore(db, new DataSourceTransactionManager(source))
                        .find(grant.id())
                        .status());
        store.revoked(grant.id());
        assertEquals("REVOKED", store.find(grant.id()).status());
    }

    @Test
    void endedSessionRoomIsNotCleanedUntilEveryGrantIsRevoked() {
        var a = active();
        var b = prepare(teacher, "TEACHER");
        monitoring.reconcile(actor(student, "STUDENT"), authority(student, "STUDENT", false), session.id(), true);
        assertEquals(2, store.pending(50).size());
        assertTrue(store.endedRooms(50).isEmpty());
        store.fence(a.id(), null);
        store.revoked(a.id());
        assertTrue(store.endedRooms(50).isEmpty());
        store.fence(b.id(), null);
        store.revoked(b.id());
        assertEquals(List.of(a.room()), store.endedRooms(50));
        store.roomDeleted(a.room());
        store.roomDeleted(a.room());
        assertTrue(store.endedRooms(50).isEmpty());
    }

    @Test
    void concurrentRenewAndFenceCannotResurrectRevocationPendingGrant() throws Exception {
        var grant = active();
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            var renewal = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return (Object) store.renew(
                            actor(student, "STUDENT"),
                            authority(student, "STUDENT", true),
                            grant.id(),
                            Duration.ofSeconds(20));
                } catch (MonitoringFailure failure) {
                    return failure;
                }
            });
            var fence = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store.fence(grant.id(), student);
            });
            Object outcome = renewal.get(10, TimeUnit.SECONDS);
            assertEquals("REVOKE_PENDING", fence.get(10, TimeUnit.SECONDS).status());
            if (outcome instanceof MonitoringFailure failure) {
                assertEquals(409, failure.status());
                assertEquals("MEDIA_GRANT_ENDED", failure.code());
            }
            assertEquals("REVOKE_PENDING", store.find(grant.id()).status());
            assertEquals(1, count("monitoring_media_grants"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void realRowLockTimeoutRollsBackPreparationAndRetryRecovers() throws Exception {
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM monitoring_sessions WHERE id=? FOR UPDATE")) {
                statement.setObject(1, session.id());
                statement.executeQuery();
            }
            failure(503, "PERSISTENCE_UNAVAILABLE", () -> prepare(student, "STUDENT"));
            assertEquals(0, count("monitoring_media_rooms"));
            assertEquals(0, count("monitoring_media_grants"));
            blocker.rollback();
        }
        assertNotNull(prepare(student, "STUDENT"));
    }

    @Test
    void failedGrantInsertRollsBackRoomBindingAndLeavesNoCredentialState() {
        db.execute(
                "CREATE FUNCTION test_reject_media() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic media failure'; END $$");
        db.execute(
                "CREATE TRIGGER test_reject_media BEFORE INSERT ON monitoring_media_grants FOR EACH ROW EXECUTE FUNCTION test_reject_media()");
        try {
            failure(503, "PERSISTENCE_UNAVAILABLE", () -> prepare(student, "STUDENT"));
            assertEquals(0, count("monitoring_media_rooms"));
            assertEquals(0, count("monitoring_media_grants"));
        } finally {
            db.execute("DROP TRIGGER test_reject_media ON monitoring_media_grants");
            db.execute("DROP FUNCTION test_reject_media()");
        }
    }

    @Test
    void bindingHistoryCannotBeDeletedOrReassignedAndRecoveryBatchIsBounded() {
        var grant = active();
        for (String sql : List.of(
                "DELETE FROM monitoring_media_grants",
                "DELETE FROM monitoring_media_rooms",
                "UPDATE monitoring_media_grants SET actor_id=gen_random_uuid()",
                "UPDATE monitoring_media_grants SET participant_identity='other'",
                "UPDATE monitoring_media_rooms SET room_name='other'"))
            assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
        assertEquals(grant, store.find(grant.id()));
        assertThrows(IllegalArgumentException.class, () -> store.pending(0));
        assertThrows(IllegalArgumentException.class, () -> store.pending(51));
        assertThrows(IllegalArgumentException.class, () -> store.endedRooms(51));
    }

    @Test
    void failedRevocationBatchCannotStarvePreviouslyUnattemptedExpiredGrants() throws Exception {
        var grants = new ArrayList<MediaStore.Lease>();
        for (int i = 0; i < 12; i++) {
            var viewer = UUID.randomUUID();
            grants.add(store.prepare(
                    actor(viewer, "TEACHER"), authority(viewer, "TEACHER", true), session.id(), Duration.ofSeconds(1)));
        }
        var latest = grants.stream()
                .map(MediaStore.Lease::leaseUntil)
                .max(Instant::compareTo)
                .orElseThrow();
        Thread.sleep(Math.max(
                0, Duration.between(Instant.now(), latest.plusMillis(30)).toMillis()));
        var unattempted =
                new HashSet<>(grants.stream().map(MediaStore.Lease::id).toList());
        var first = store.pending(10);
        assertEquals(10, first.size());
        for (var grant : first) {
            assertNotNull(store.fence(grant.id(), null));
            unattempted.remove(grant.id());
        }
        assertEquals(2, unattempted.size());
        var next = store.pending(10);
        assertEquals(
                unattempted,
                new HashSet<>(
                        next.subList(0, 2).stream().map(MediaStore.Lease::id).toList()));
        assertEquals(12, count("monitoring_media_grants"));
    }

    @Test
    void failedRoomDeletionBatchCannotStarvePreviouslyUnattemptedEndedRooms() {
        var unattempted = new HashSet<String>();
        for (int i = 0; i < 12; i++) {
            attempt = UUID.randomUUID();
            session = monitoring.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
            var grant = prepare(student, "STUDENT");
            store.fence(grant.id(), student);
            store.revoked(grant.id());
            monitoring.reconcile(actor(student, "STUDENT"), authority(student, "STUDENT", false), session.id(), true);
            unattempted.add(grant.room());
        }
        var first = store.endedRooms(10);
        assertEquals(10, first.size());
        unattempted.removeAll(first);
        assertEquals(2, unattempted.size());
        var next = store.endedRooms(10);
        assertEquals(unattempted, new HashSet<>(next.subList(0, 2)));
        assertEquals(12, count("monitoring_media_rooms"));
    }

    @Test
    void realV1ToV2UpgradePreservesChecksumAndExistingMonitoringEvidence() {
        var schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var separator = DATABASE.getJdbcUrl().contains("?") ? "&" : "?";
        var upgradeSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl() + separator + "currentSchema=" + schema,
                DATABASE.getUsername(),
                DATABASE.getPassword());
        try {
            Flyway.configure()
                    .dataSource(upgradeSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("1")
                    .load()
                    .migrate();
            var upgradeDb = new JdbcTemplate(upgradeSource);
            var oldStore = new JdbcMonitoringStore(
                    upgradeDb, new DataSourceTransactionManager(upgradeSource), new MonitoringProperties());
            var oldSession = oldStore.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
            var eventId = UUID.randomUUID();
            oldStore.append(
                    actor(student, "STUDENT"),
                    authority(student, "STUDENT", true),
                    oldSession.id(),
                    new com.quizopia.proctoring.domain.Monitoring.Event(
                            eventId,
                            UUID.randomUUID(),
                            1L,
                            com.quizopia.proctoring.domain.Monitoring.EventType.VISIBILITY_HIDDEN,
                            Instant.now(),
                            null,
                            1));
            var checksum = upgradeDb.queryForObject(
                    "SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class);
            var sessionsBefore = upgradeDb.queryForList("SELECT * FROM monitoring_sessions ORDER BY id");
            var evidenceBefore = upgradeDb.queryForList("SELECT * FROM monitoring_evidence ORDER BY ordinal");
            var upgrade = Flyway.configure()
                    .dataSource(upgradeSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("2")
                    .load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            upgrade.validate();
            assertEquals(
                    checksum,
                    upgradeDb.queryForObject(
                            "SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class));
            assertEquals(sessionsBefore, upgradeDb.queryForList("SELECT * FROM monitoring_sessions ORDER BY id"));
            assertEquals(evidenceBefore, upgradeDb.queryForList("SELECT * FROM monitoring_evidence ORDER BY ordinal"));
            assertEquals(2, evidenceBefore.size());
            assertEquals(0, upgradeDb.queryForObject("SELECT count(*) FROM monitoring_media_grants", Integer.class));
            assertEquals(
                    List.of("1", "2"),
                    upgradeDb.queryForList(
                            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                            String.class));
        } finally {
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}

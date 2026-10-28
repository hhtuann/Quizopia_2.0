package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.*;
import com.quizopia.proctoring.integration.*;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import com.rabbitmq.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Owned real PostgreSQL/RabbitMQ; no existing broker, learner or cloud resources are used. */
@Testcontainers
class AssessmentEventConsumerIntegrationTest {
    static final String ADMIN = "fixture-admin", PUBLISHER = "assessment-writer", CONSUMER = "proctoring-reader";
    static final String EXCHANGE = "quizopia.assessment.monitoring.v1", ROUTING = "answer.persisted.v1";
    static final String PASSWORD = UUID.randomUUID().toString(), VHOST = "fixture-monitoring";

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", ADMIN)
            .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    static DriverManagerDataSource source;
    static JdbcTemplate db;
    AssessmentEventInbox inbox;
    JdbcMonitoringStore store;
    AssessmentEventsProperties properties;
    SimpleMeterRegistry meters;
    AssessmentAnswerEvent event;

    @BeforeAll
    static void resources() throws Exception {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
        ctl("add_user", PUBLISHER, PASSWORD);
        ctl("add_user", CONSUMER, PASSWORD);
        ctl("set_permissions", "-p", VHOST, PUBLISHER, "a^", "^quizopia\\.assessment\\.monitoring\\.v1$", "a^");
        ctl(
                "set_permissions",
                "-p",
                VHOST,
                CONSUMER,
                "a^",
                "a^",
                "^quizopia\\.proctoring\\.assessment-monitoring\\.v1$");
        try (var connection = broker(ADMIN);
                var channel = connection.createChannel()) {
            channel.exchangeDeclare(EXCHANGE, "direct", true);
            channel.queueDeclare(AssessmentEventConsumer.QUEUE, true, false, false, Map.of());
            channel.queueBind(AssessmentEventConsumer.QUEUE, EXCHANGE, ROUTING);
        }
    }

    static void ctl(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("rabbitmqctl");
        command.addAll(List.of(args));
        var result = BROKER.execInContainer(command.toArray(String[]::new));
        assertEquals(0, result.getExitCode(), "Owned RabbitMQ fixture command failed");
    }

    static com.rabbitmq.client.Connection broker(String username) throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(BROKER.getMappedPort(5672));
        factory.setVirtualHost(VHOST);
        factory.setUsername(username);
        factory.setPassword(PASSWORD);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setConnectionTimeout(2000);
        factory.setChannelRpcTimeout(2000);
        return factory.newConnection();
    }

    @BeforeEach
    void fixture() throws Exception {
        db.execute(
                "TRUNCATE assessment_monitoring_inbox,assessment_monitoring_rejections,monitoring_sessions RESTART IDENTITY CASCADE");
        try (var connection = broker(ADMIN);
                var channel = connection.createChannel()) {
            channel.queuePurge(AssessmentEventConsumer.QUEUE);
        }
        var monitoring = new MonitoringProperties();
        inbox = new AssessmentEventInbox(db, new DataSourceTransactionManager(source), monitoring);
        store = new JdbcMonitoringStore(db, new DataSourceTransactionManager(source), monitoring);
        properties = new AssessmentEventsProperties();
        properties.setEnabled(true);
        properties.setTls(false);
        properties.setAllowInsecureLoopback(true);
        properties.setHost("127.0.0.1");
        properties.setPort(BROKER.getMappedPort(5672));
        properties.setVirtualHost(VHOST);
        properties.setUsername(CONSUMER);
        properties.setPassword(PASSWORD);
        properties.setPublisherIdentity(PUBLISHER);
        properties.validate(false);
        meters = new SimpleMeterRegistry();
        var start = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        event = new AssessmentAnswerEvent(
                UUID.randomUUID(),
                AssessmentAnswerEvent.TYPE,
                1,
                start.plusSeconds(10),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                start,
                start.plusSeconds(600),
                1,
                "1");
    }

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    MonitoringAuthority.Actor actor(String role) {
        return new MonitoringAuthority.Actor(
                role.equals("STUDENT") ? event.studentId() : UUID.randomUUID(),
                Set.of(role),
                "synthetic-user-evidence",
                Instant.now().plusSeconds(600));
    }

    MonitoringAuthority.Snapshot authority(MonitoringAuthority.Actor actor, boolean active) {
        return new MonitoringAuthority.Snapshot(
                actor.id(),
                actor.roles().iterator().next(),
                event.attemptId(),
                event.publicationId(),
                event.classroomId(),
                event.studentId(),
                active,
                event.deadlineAt(),
                Instant.now());
    }

    Session session() {
        var student = actor("STUDENT");
        return store.create(student, authority(student, true));
    }

    AssessmentAnswerEvent copy(UUID id, UUID publication, int question, String revision) {
        return new AssessmentAnswerEvent(
                id,
                event.eventType(),
                1,
                event.occurredAt(),
                event.attemptId(),
                publication,
                event.classroomId(),
                event.studentId(),
                event.attemptStartedAt(),
                event.deadlineAt(),
                question,
                revision);
    }

    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    String state(UUID id) {
        return db.queryForObject("SELECT state FROM assessment_monitoring_inbox WHERE event_id=?", String.class, id);
    }

    AMQP.BasicProperties.Builder metadata() {
        return new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .appId("assessment-service")
                .userId(PUBLISHER)
                .type(AssessmentAnswerEvent.TYPE)
                .messageId(event.eventId().toString());
    }

    void publish(byte[] bytes, AMQP.BasicProperties metadata) throws Exception {
        try (var connection = broker(PUBLISHER);
                var channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(EXCHANGE, ROUTING, metadata, bytes);
            channel.waitForConfirmsOrDie(3000);
        }
    }

    int queued() throws Exception {
        try (var connection = broker(ADMIN);
                var channel = connection.createChannel()) {
            return channel.queueDeclarePassive(AssessmentEventConsumer.QUEUE).getMessageCount();
        }
    }

    void poll() {
        new AssessmentEventConsumer(inbox, properties, meters).poll();
    }

    @Test
    void durableMissingSessionWaitsAndLaterAttachesExactlyOnce() throws Exception {
        publish(event.bytes(), metadata().build());
        poll();
        assertEquals(0, queued());
        assertEquals("PENDING", state(event.eventId()));
        assertEquals(0, count("monitoring_sessions"));
        var session = session();
        var restarted =
                new AssessmentEventInbox(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        assertEquals(1, restarted.drain());
        assertEquals(0, restarted.drain());
        inbox.accept(event);
        assertEquals(0, inbox.drain());
        assertEquals("APPLIED", state(event.eventId()));
        assertEquals(2, count("monitoring_evidence"));
        var teacher = actor("TEACHER");
        var page = store.timeline(teacher, authority(teacher, true), session.id(), 0, 100);
        var fact = page.items().stream()
                .filter(e -> e.type() == EventType.ANSWER_PERSISTED)
                .findFirst()
                .orElseThrow();
        assertEquals("ASSESSMENT_AUTHORITY", fact.source());
        assertEquals("1", fact.assessmentRevision());
        assertEquals(event.studentId(), fact.actorId());
        assertEquals(event.occurredAt(), fact.observedAt());
    }

    @Test
    void historicalFactAfterLocalEndCannotResurrectSession() {
        var session = session();
        var student = actor("STUDENT");
        store.reconcile(student, authority(student, false), session.id(), true);
        inbox.accept(event);
        assertEquals(1, inbox.drain());
        assertEquals("ENDED", store.find(session.id()).status());
        assertEquals(3, count("monitoring_evidence"));
        assertEquals("APPLIED", state(event.eventId()));
    }

    @Test
    void changedEventIdentityAndRepeatedRevisionAreDurablyQuarantined() {
        session();
        inbox.accept(event);
        assertEquals(1, inbox.drain());
        inbox.accept(copy(event.eventId(), event.publicationId(), 2, "1"));
        var collision = copy(UUID.randomUUID(), event.publicationId(), 1, "1");
        inbox.accept(collision);
        assertEquals(1, inbox.drain());
        assertEquals("QUARANTINED", state(collision.eventId()));
        assertEquals(2, count("assessment_monitoring_rejections"));
        assertEquals(2, count("monitoring_evidence"));
        assertEquals("APPLIED", state(event.eventId()));
    }

    @Test
    void mismatchedBindingNeverCopiesEvidenceAndDoesNotBlockOtherEvents() {
        session();
        var conflict = copy(UUID.randomUUID(), UUID.randomUUID(), 1, "1");
        inbox.accept(conflict);
        inbox.accept(event);
        assertEquals(2, inbox.drain());
        assertEquals("QUARANTINED", state(conflict.eventId()));
        assertEquals("APPLIED", state(event.eventId()));
        assertEquals(
                "BINDING_CONFLICT",
                db.queryForObject("SELECT reason FROM assessment_monitoring_rejections", String.class));
        assertEquals(2, count("monitoring_evidence"));
    }

    @Test
    void twoInstancesDrainBoundedBatchesWithoutDuplicateEvidence() throws Exception {
        session();
        for (int i = 1; i <= 25; i++) inbox.accept(copy(UUID.randomUUID(), event.publicationId(), i, "1"));
        var other = new AssessmentEventInbox(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(inbox::drain);
            var second = executor.submit(other::drain);
            assertEquals(10, first.get(15, TimeUnit.SECONDS));
            assertEquals(10, second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(5, inbox.drain());
        assertEquals(26, count("monitoring_evidence"));
        assertEquals(
                25,
                db.queryForObject(
                        "SELECT count(*) FROM assessment_monitoring_inbox WHERE state='APPLIED'", Integer.class));
    }

    @Test
    void teacherTimelineChecksExtendedHashAndKeepsOriginalHashesValid() {
        var session = session();
        var student = actor("STUDENT");
        store.append(
                student,
                authority(student, true),
                session.id(),
                new Event(UUID.randomUUID(), UUID.randomUUID(), 1L, EventType.WINDOW_FOCUS, null, null, 1));
        inbox.accept(event);
        inbox.drain();
        var teacher = actor("TEACHER");
        assertEquals(
                3,
                store.timeline(teacher, authority(teacher, true), session.id(), 0, 100)
                        .items()
                        .size());
        db.execute("ALTER TABLE monitoring_evidence DISABLE TRIGGER monitoring_evidence_guard");
        try {
            db.update("UPDATE monitoring_evidence SET assessment_revision=2 WHERE id=?", event.eventId());
        } finally {
            db.execute("ALTER TABLE monitoring_evidence ENABLE TRIGGER monitoring_evidence_guard");
        }
        var failure = assertThrows(
                MonitoringFailure.class, () -> store.timeline(teacher, authority(teacher, true), session.id(), 0, 100));
        assertEquals("EVIDENCE_INTEGRITY_UNAVAILABLE", failure.code());
    }

    @Test
    void immutableInboxAndEvidenceCannotBeRewrittenByNormalOperations() {
        session();
        inbox.accept(event);
        inbox.drain();
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "UPDATE assessment_monitoring_inbox SET payload='{}' WHERE event_id=?", event.eventId()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("DELETE FROM assessment_monitoring_inbox WHERE event_id=?", event.eventId()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("UPDATE monitoring_evidence SET assessment_revision=2 WHERE id=?", event.eventId()));
    }

    @Test
    void malformedAndWrongMetadataAreFingerprintOnlyQuarantinedBeforeAck() throws Exception {
        for (AMQP.BasicProperties bad : List.of(
                metadata().appId("other-service").build(),
                metadata().type("WRONG").build(),
                metadata().messageId(UUID.randomUUID().toString()).build(),
                metadata().deliveryMode(1).build(),
                metadata().contentType("text/plain").build(),
                metadata().userId(null).build())) {
            publish(event.bytes(), bad);
            poll();
            assertEquals(0, queued());
        }
        byte[] bad = "{\"answer\":\"secret-must-not-persist\"}".getBytes(StandardCharsets.UTF_8);
        publish(bad, metadata().build());
        poll();
        assertEquals(0, queued());
        assertEquals(0, count("assessment_monitoring_inbox"));
        assertEquals(2, count("assessment_monitoring_rejections"));
        assertEquals(
                6L,
                db.queryForObject(
                        "SELECT occurrences FROM assessment_monitoring_rejections WHERE occurrences>1", Long.class));
        var columns = db.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name='assessment_monitoring_rejections'",
                String.class);
        assertFalse(columns.contains("payload"));
        assertFalse(columns.contains("student_id"));
        assertEquals(
                7.0,
                meters.counter("proctoring.assessment.events", "outcome", "rejected")
                        .count());
    }

    @Test
    void brokerChecksPublisherIdentityAndReadOnlyConsumerCannotPublish() throws Exception {
        try (var connection = broker(PUBLISHER)) {
            var channel = connection.createChannel();
            channel.confirmSelect();
            channel.basicPublish(
                    EXCHANGE, ROUTING, metadata().userId("forged-identity").build(), event.bytes());
            assertThrows(Exception.class, () -> channel.waitForConfirmsOrDie(3000));
        }
        try (var connection = broker(CONSUMER)) {
            var channel = connection.createChannel();
            channel.confirmSelect();
            channel.basicPublish(EXCHANGE, ROUTING, metadata().userId(CONSUMER).build(), event.bytes());
            assertThrows(Exception.class, () -> channel.waitForConfirmsOrDie(3000));
        }
        assertEquals(0, queued());
    }

    @Test
    void databaseFailureNacksAndReconstructedConsumerRecoversRedelivery() throws Exception {
        publish(event.bytes(), metadata().build());
        try (Connection lock = source.getConnection()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE assessment_monitoring_inbox IN ACCESS EXCLUSIVE MODE");
            poll();
            assertEquals(1, queued());
            lock.rollback();
        }
        try (var connection = broker(ADMIN);
                var channel = connection.createChannel()) {
            var delivery = channel.basicGet(AssessmentEventConsumer.QUEUE, false);
            assertNotNull(delivery);
            assertTrue(delivery.getEnvelope().isRedeliver());
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
        }
        inbox = new AssessmentEventInbox(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        poll();
        assertEquals(0, queued());
        assertEquals("PENDING", state(event.eventId()));
        session();
        assertEquals(1, inbox.drain());
        assertEquals(2, count("monitoring_evidence"));
    }

    @Test
    void realV2ToV3UpgradePreservesHistoricalEvidenceBindingsAndChecksums() {
        String schema = "event_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var upgradeSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl() + (DATABASE.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                DATABASE.getUsername(),
                DATABASE.getPassword());
        try {
            Flyway.configure()
                    .dataSource(upgradeSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("2")
                    .load()
                    .migrate();
            var upgradeDb = new JdbcTemplate(upgradeSource);
            var old = new JdbcMonitoringStore(
                    upgradeDb, new DataSourceTransactionManager(upgradeSource), new MonitoringProperties());
            var student = actor("STUDENT");
            var session = old.create(student, authority(student, true));
            var checksums = upgradeDb.queryForList(
                    "SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
            var sessions = upgradeDb.queryForList("SELECT * FROM monitoring_sessions");
            var evidence = upgradeDb.queryForList("SELECT * FROM monitoring_evidence ORDER BY ordinal");
            var upgrade = Flyway.configure()
                    .dataSource(upgradeSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();
            // V3 (answer metadata inbox) and V4 (FB-015 fence/purge surface) both apply now.
            assertEquals(2, upgrade.migrate().migrationsExecuted);
            upgrade.validate();
            assertEquals(
                    checksums,
                    upgradeDb.queryForList(
                            "SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank"));
            // V4 adds the nullable quiz_id binding column; existing rows keep NULL.
            var afterSessions = upgradeDb.queryForList("SELECT * FROM monitoring_sessions");
            for (var row : afterSessions) row.remove("quiz_id");
            assertEquals(sessions, afterSessions);
            var after = upgradeDb.queryForList("SELECT * FROM monitoring_evidence ORDER BY ordinal");
            assertEquals(evidence.size(), after.size());
            for (int i = 0; i < after.size(); i++) {
                assertNull(after.get(i).remove("assessment_revision"));
                assertEquals(evidence.get(i), after.get(i));
            }
            var teacher = actor("TEACHER");
            assertEquals(
                    1,
                    old.timeline(teacher, authority(teacher, true), session.id(), 0, 100)
                            .items()
                            .size());
            assertEquals(
                    List.of("1", "2", "3", "4"),
                    upgradeDb.queryForList(
                            "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                            String.class));
        } finally {
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void consumerIsDefaultClosedAndPollsAtMostTenDeliveries() throws Exception {
        for (int i = 1; i <= 12; i++) {
            var next = copy(UUID.randomUUID(), event.publicationId(), i, "1");
            publish(
                    next.bytes(),
                    metadata().messageId(next.eventId().toString()).build());
        }
        properties.setEnabled(false);
        poll();
        assertEquals(12, queued());
        assertEquals(0, count("assessment_monitoring_inbox"));
        properties.setEnabled(true);
        poll();
        assertEquals(2, queued());
        assertEquals(10, count("assessment_monitoring_inbox"));
        poll();
        assertEquals(0, queued());
        assertEquals(12, count("assessment_monitoring_inbox"));
        assertEquals(0, count("monitoring_sessions"));
    }

    @Test
    void defaultTlsTrustRejectsUnknownCertificateBeforeBrokerCredentialsAreSent() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("quizopia-consumer-tls-");
        if (java.nio.file.Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            java.nio.file.Files.setPosixFilePermissions(
                    directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        var keyFile = directory.resolve("server.p12");
        String pass = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var keytool = new ProcessBuilder(
                            System.getProperty("java.home") + "/bin/keytool",
                            "-genkeypair",
                            "-alias",
                            "fixture",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            keyFile.toString(),
                            "-storepass",
                            pass,
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "SAN=dns:localhost,ip:127.0.0.1")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertTrue(keytool.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, keytool.exitValue());
            if (java.nio.file.Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
                java.nio.file.Files.setPosixFilePermissions(
                        keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            var keys = java.security.KeyStore.getInstance("PKCS12");
            try (var input = java.nio.file.Files.newInputStream(keyFile)) {
                keys.load(input, pass.toCharArray());
            }
            var certificateChain = Arrays.stream(keys.getCertificateChain("fixture"))
                    .map(java.security.cert.X509Certificate.class::cast)
                    .toArray(java.security.cert.X509Certificate[]::new);
            var defaultTrustManagers = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            defaultTrustManagers.init((java.security.KeyStore) null);
            var defaultTrustManager = Arrays.stream(defaultTrustManagers.getTrustManagers())
                    .filter(javax.net.ssl.X509TrustManager.class::isInstance)
                    .map(javax.net.ssl.X509TrustManager.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThrows(
                    java.security.cert.CertificateException.class,
                    () -> defaultTrustManager.checkServerTrusted(certificateChain, "RSA"));
            var managers =
                    javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, pass.toCharArray());
            var tls = javax.net.ssl.SSLContext.getInstance("TLS");
            tls.init(managers.getKeyManagers(), null, null);
            try (var server = (javax.net.ssl.SSLServerSocket)
                    tls.getServerSocketFactory().createServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                server.setSoTimeout(5000);
                var handshake = executor.submit(() -> {
                    try (var socket = (javax.net.ssl.SSLSocket) server.accept()) {
                        socket.setSoTimeout(5000);
                        socket.startHandshake();
                        return null;
                    } catch (javax.net.ssl.SSLHandshakeException expected) {
                        return expected;
                    } catch (java.net.SocketException expectedPeerAbort) {
                        String message = Objects.requireNonNullElse(expectedPeerAbort.getMessage(), "")
                                .toLowerCase(Locale.ROOT);
                        boolean expectedWindowsPeerAbort = System.getProperty("os.name")
                                        .toLowerCase(Locale.ROOT)
                                        .contains("win")
                                && (message.contains("forcibly closed by the remote host")
                                        || message.contains("aborted by the software in your host machine"));
                        if (!expectedWindowsPeerAbort) throw expectedPeerAbort;
                        return expectedPeerAbort;
                    }
                });
                properties.setTls(true);
                properties.setPort(server.getLocalPort());
                poll();
                assertNotNull(handshake.get(8, TimeUnit.SECONDS));
                assertEquals(
                        1.0,
                        meters.counter("proctoring.assessment.events", "outcome", "pending")
                                .count());
                assertEquals(
                        0.0,
                        meters.counter("proctoring.assessment.events", "outcome", "received")
                                .count());
                assertEquals(
                        0.0,
                        meters.counter("proctoring.assessment.events", "outcome", "rejected")
                                .count());
                assertEquals(0, count("assessment_monitoring_inbox"));
            }
        } finally {
            executor.shutdownNow();
            java.nio.file.Files.deleteIfExists(keyFile);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }
}

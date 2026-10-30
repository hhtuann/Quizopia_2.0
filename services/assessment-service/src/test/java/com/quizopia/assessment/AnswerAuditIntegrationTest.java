package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.audit.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class AnswerAuditIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    AtomicFinalizationIntegrationTest.Seed publication;
    UUID attempt;
    ClosureStore.Teacher teacher;
    AnswerAuditStore store;
    AnswerAuditService service;
    byte[] key = new byte[32];
    SensitiveAssessmentPolicy permit = now -> now.plusSeconds(3600); // disposable fixture expiry, not product retention

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        publication = f.publication(0);
        attempt = f.active(publication, 0);
        teacher = new ClosureStore.Teacher(
                UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", publication.publication())),
                f.now().plusSeconds(3600),
                "fixture");
        new java.security.SecureRandom().nextBytes(key);
        store = new AnswerAuditStore(
                f.db,
                f.manager,
                permit,
                new AuditEncryption.AesGcm("synthetic", key),
                AssessmentTransactionProperties.defaults());
        service = new AnswerAuditService(store, mock(PublicationSources.class));
    }

    byte[] observation(UUID event, UUID stream, long sequence, String type, String from, String to) {
        String detail =
                from == null ? "" : ",\"transition\":{\"fromOptionLabels\":" + from + ",\"toOptionLabels\":" + to + "}";
        return ("{\"schemaVersion\":1,\"eventId\":\"" + event + "\",\"streamId\":\"" + stream + "\",\"sequence\":"
                        + sequence + ",\"type\":\"" + type + "\",\"questionNumber\":1" + detail + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    AnswerAuditStore.Ack append(byte[] body) {
        return service.append(
                f.student, f.now().plusSeconds(3600), "fixture", publication.publication(), attempt, body);
    }

    void failure(String code, Runnable work) {
        f.failure(code, work);
    }

    @Test
    void unsavedAToCIsProtectedThenVisibleOnlyAfterGradedSubmitWithReceipt() {
        UUID stream = UUID.randomUUID();
        var selected = observation(UUID.randomUUID(), stream, 1, "ANSWER_SELECTED", "[]", "[\"A\"]");
        var changed = observation(UUID.randomUUID(), stream, 2, "ANSWER_CHANGED", "[\"A\"]", "[\"C\"]");
        var first = append(selected);
        var second = append(changed);
        assertEquals("1", first.ordinal());
        assertEquals("2", second.ordinal());
        assertEquals(first, append(selected));
        assertEquals("0", f.scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", attempt));
        var ciphertext = f.db.queryForObject(
                "SELECT protected_input FROM assessment_answer_audit WHERE event_id=?", byte[].class, first.eventId());
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("fromOptionLabels"));
        failure("REVIEW_NOT_AVAILABLE", () -> service.timeline(teacher, publication.publication(), attempt, 0, 50));
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        var timeline = service.timeline(teacher, publication.publication(), attempt, 0, 1);
        assertEquals("PARTIAL", timeline.coverage().status());
        assertEquals("1", timeline.nextAfter());
        assertEquals("CLIENT_OBSERVATION", timeline.items().getFirst().source());
        assertEquals("AVAILABLE", timeline.items().getFirst().transitionDetailStatus());
        var last = service.timeline(teacher, publication.publication(), attempt, 1, 50)
                .items()
                .getFirst();
        assertEquals(
                "C",
                SnapshotFixtures.JSON
                        .valueToTree(last.transition())
                        .path("toOptionLabels")
                        .get(0)
                        .asString());
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
        assertEquals(first, append(selected)); // exact replay is metadata-only after terminalization
        failure(
                "ATTEMPT_TERMINAL",
                () -> append(observation(UUID.randomUUID(), stream, 3, "ANSWER_CLEARED", "[\"C\"]", "[]")));
    }

    @Test
    void changedEventOrSequenceConflictsAndOutOfOrderIsNotRewritten() {
        UUID stream = UUID.randomUUID(), event = UUID.randomUUID();
        var body = observation(event, stream, 8, "ANSWER_CHANGED", "[\"A\"]", "[\"C\"]");
        append(body);
        failure(
                "EVENT_CONFLICT",
                () -> append(observation(event, UUID.randomUUID(), 8, "ANSWER_CHANGED", "[\"A\"]", "[\"C\"]")));
        failure("EVENT_CONFLICT", () -> append(observation(event, stream, 9, "ANSWER_CHANGED", "[\"A\"]", "[\"C\"]")));
        failure("EVENT_CONFLICT", () -> append(observation(event, stream, 8, "ANSWER_CHANGED", "[\"A\"]", "[\"B\"]")));
        failure(
                "EVENT_CONFLICT",
                () -> append(observation(UUID.randomUUID(), stream, 8, "ANSWER_CHANGED", "[\"A\"]", "[\"C\"]")));
        assertEquals(
                "2",
                append(observation(UUID.randomUUID(), stream, 1, "ANSWER_SELECTED", "[]", "[\"A\"]"))
                        .ordinal());
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }

    @Test
    void concurrentIdenticalAppendsProduceOneDurableAcknowledgment() throws Exception {
        var body = observation(UUID.randomUUID(), UUID.randomUUID(), 1, "ANSWER_SELECTED", "[]", "[\"A\"]");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<AnswerAuditStore.Ack> call = () -> {
                start.await();
                return append(body);
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }

    @Test
    void pendingPolicyWrongActorCrossPublicationAndExpiredActorCannotCollect() {
        var blocked = new AnswerAuditStore(
                f.db,
                f.manager,
                new SensitiveAssessmentPolicy.Pending(),
                new AuditEncryption.Pending(),
                AssessmentTransactionProperties.defaults());
        failure("SENSITIVE_POLICY_PENDING", blocked::requirePolicy);
        var body = observation(UUID.randomUUID(), UUID.randomUUID(), 1, "ANSWER_SELECTED", "[]", "[\"A\"]");
        failure(
                "ATTEMPT_NOT_FOUND",
                () -> service.append(
                        UUID.randomUUID(),
                        f.now().plusSeconds(60),
                        "foreign",
                        publication.publication(),
                        attempt,
                        body));
        failure(
                "ATTEMPT_NOT_FOUND",
                () -> service.append(f.student, f.now().plusSeconds(60), "fixture", UUID.randomUUID(), attempt, body));
        failure(
                "UNAUTHENTICATED",
                () -> service.append(
                        f.student, f.now().minusSeconds(1), "fixture", publication.publication(), attempt, body));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }

    @Test
    void appendGuardsAndBoundedPurgeRespectHoldsFencesAndRoleSeparation() throws Exception {
        append(observation(UUID.randomUUID(), UUID.randomUUID(), 1, "ANSWER_SELECTED", "[]", "[\"A\"]"));
        assertThrows(RuntimeException.class, () -> f.db.update("UPDATE assessment_answer_audit SET ordinal=99"));
        assertThrows(RuntimeException.class, () -> f.db.update("DELETE FROM assessment_answer_audit"));
        assertEquals("f", f.scalar("SELECT has_table_privilege('assessment','assessment_answer_audit','DELETE')"));
        assertEquals(
                "f",
                f.scalar(
                        "SELECT has_function_privilege('assessment','assessment_purge_quiz_v11(uuid,uuid,integer)','EXECUTE')"));
        UUID operation = UUID.randomUUID();
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source,ack_at) VALUES(?,?,clock_timestamp(),'COMMAND',clock_timestamp())",
                publication.configuration().quizId(),
                operation);
        failure(
                "ASSESSMENT_REMOVED",
                () -> append(observation(UUID.randomUUID(), UUID.randomUUID(), 2, "QUESTION_VIEWED", null, null)));
        UUID hold = UUID.randomUUID();
        f.db.update(
                "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES(?,?,'synthetic custodian','disposable fixture hold','synthetic reviewer',clock_timestamp()+interval '1 hour')",
                hold,
                publication.configuration().quizId());
        assertEquals(
                "BLOCKED_BY_RETENTION",
                f.db.queryForObject(
                        "SELECT assessment_purge_quiz(?,?,1)",
                        String.class,
                        publication.configuration().quizId(),
                        operation));
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        f.db.update("DELETE FROM retention_holds WHERE id=?", hold);
        String stage = "IN_PROGRESS";
        int rounds = 0;
        while (stage.equals("IN_PROGRESS") && rounds++ < 50)
            stage = f.db.queryForObject(
                    "SELECT assessment_purge_quiz(?,?,1)",
                    String.class,
                    publication.configuration().quizId(),
                    operation);
        assertEquals("PURGED", stage);
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        assertEquals("0", f.scalar("SELECT count(*) FROM attempts WHERE id=?", attempt));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
    }

    @Test
    void acceptedEventRateIsBoundedAndExactReplayDoesNotConsumeAnotherSlot() {
        UUID stream = UUID.randomUUID();
        byte[] first = null;
        for (int n = 0; n < 60; n++) {
            var body = observation(UUID.randomUUID(), stream, n, "QUESTION_VIEWED", null, null);
            if (n == 0) first = body;
            append(body);
        }
        assertNotNull(first);
        append(first);
        failure(
                "AUDIT_RATE_LIMITED",
                () -> append(observation(UUID.randomUUID(), stream, 60, "QUESTION_VIEWED", null, null)));
        assertEquals("60", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }

    @Test
    void failedAppendAndFailedAccessReceiptAreAtomicAndRecoverable() {
        var body = observation(UUID.randomUUID(), UUID.randomUUID(), 1, "ANSWER_SELECTED", "[]", "[\"A\"]");
        f.db.execute(
                "CREATE FUNCTION synthetic_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        f.db.execute(
                "CREATE TRIGGER synthetic_audit_failure BEFORE INSERT ON assessment_answer_audit FOR EACH ROW EXECUTE FUNCTION synthetic_audit_failure()");
        failure("AUDIT_UNAVAILABLE", () -> append(body));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        f.db.execute("DROP TRIGGER synthetic_audit_failure ON assessment_answer_audit");
        assertEquals("1", append(body).ordinal());
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        f.db.execute(
                "CREATE TRIGGER synthetic_receipt_failure BEFORE INSERT ON assessment_sensitive_read_receipts FOR EACH ROW EXECUTE FUNCTION synthetic_audit_failure()");
        failure("AUDIT_UNAVAILABLE", () -> service.timeline(teacher, publication.publication(), attempt, 0, 50));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
    }

    @Test
    void expiredHistoryIsOmittedAndLegacyIsNeverReconstructed() {
        append(observation(UUID.randomUUID(), UUID.randomUUID(), 1, "ANSWER_SELECTED", "[]", "[\"A\"]"));
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE AS $$ SELECT clock_timestamp()+interval '2 hours' $$");
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        var current = f.db.queryForObject(
                "SELECT assessment_time()", (row, n) -> row.getTimestamp(1).toInstant());
        teacher = new ClosureStore.Teacher(teacher.id(), current.plusSeconds(3600), teacher.evidence());
        var expired = service.timeline(teacher, publication.publication(), attempt, 0, 50);
        assertTrue(expired.items().isEmpty());
        assertEquals("PARTIAL", expired.coverage().status());
        assertTrue(expired.coverage().reasons().contains("EXPIRED"));
        f.student = UUID.randomUUID();
        var legacy = f.active(publication, 0);
        f.core.submit(f.manual(legacy, 0, GradingDocumentsTest.replacement()));
        var missing = service.timeline(teacher, publication.publication(), legacy, 0, 50);
        assertTrue(missing.items().isEmpty());
        assertEquals("NOT_COLLECTED", missing.coverage().status());
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }
}

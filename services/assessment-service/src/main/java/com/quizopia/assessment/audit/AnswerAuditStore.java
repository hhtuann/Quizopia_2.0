package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic-verifiable append-only foundation. No production permit or automatic capture hooks. */
@Repository
public class AnswerAuditStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SensitiveAssessmentPolicy policy;
    private final AuditEncryption encryption;
    private final AssessmentTransactionProperties limits;
    private final JsonMapper json = JsonMapper.builder().build();

    public AnswerAuditStore(
            @Lazy JdbcTemplate db,
            @Lazy PlatformTransactionManager manager,
            SensitiveAssessmentPolicy policy,
            AuditEncryption encryption,
            AssessmentTransactionProperties limits) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.policy = policy;
        this.encryption = encryption;
        this.limits = limits;
        tx.setTimeout(limits.timeoutSeconds());
    }

    public record Binding(
            UUID publicationId,
            UUID attemptId,
            UUID studentId,
            UUID classroomId,
            UUID ownerId,
            String state,
            String publicationState) {}

    public record Ack(UUID eventId, String ordinal, Instant receivedAt) {}

    public record Item(
            UUID eventId,
            String ordinal,
            String type,
            String source,
            int questionNumber,
            Instant observedAt,
            Instant receivedAt,
            String transitionDetailStatus,
            Object transition) {
        public String toString() {
            return "AuditItem[redacted]";
        }
    }

    public record Coverage(String status, List<String> reasons) {}

    public record Timeline(List<Item> items, String nextAfter, Coverage coverage) {
        public String toString() {
            return "AuditTimeline[redacted]";
        }
    }

    private static PublicationFailure unavailable() {
        return new PublicationFailure(503, "AUDIT_UNAVAILABLE");
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
            return tx.execute(s -> {
                db.execute("SET LOCAL lock_timeout='" + limits.lockMillis() + "ms'");
                db.execute("SET LOCAL statement_timeout='" + limits.statementMillis() + "ms'");
                return work.get();
            });
        } catch (PublicationFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private Binding binding(
            UUID actor, Instant expires, UUID publication, UUID attempt, boolean teacher, boolean lock) {
        // Explicit publication-first lock order, shared with START/SAVE/finalizers/fences.
        var pubs = db.query(
                "SELECT p.owner_id,p.classroom_id,p.state,p.quiz_id FROM publications p WHERE p.id=? AND "
                        + (teacher
                                ? "p.owner_id=?"
                                : "EXISTS(SELECT FROM attempts a WHERE a.publication_id=p.id AND a.student_id=? AND a.id=?)")
                        + " FOR " + (lock ? "UPDATE" : "SHARE") + " OF p",
                (r, n) -> new PublicationBinding(
                        r.getObject("owner_id", UUID.class),
                        r.getObject("classroom_id", UUID.class),
                        r.getString("state"),
                        r.getObject("quiz_id", UUID.class)),
                teacher ? new Object[] {publication, actor} : new Object[] {publication, actor, attempt});
        if (pubs.size() != 1) throw new PublicationFailure(404, "ATTEMPT_NOT_FOUND");
        var pub = pubs.getFirst();
        new QuizRevocationGate(db).requireQuizOpen(pub.quiz());
        var rows = db.query(
                "SELECT student_id,state FROM attempts WHERE id=? AND publication_id=? FOR "
                        + (lock ? "UPDATE" : "SHARE"),
                (r, n) -> new Binding(
                        publication,
                        attempt,
                        r.getObject("student_id", UUID.class),
                        pub.classroom(),
                        pub.owner(),
                        r.getString("state"),
                        pub.state()),
                attempt,
                publication);
        if (rows.size() != 1) throw new PublicationFailure(404, "ATTEMPT_NOT_FOUND");
        currentActor(expires);
        return rows.getFirst();
    }

    private record PublicationBinding(UUID owner, UUID classroom, String state, UUID quiz) {}

    private void currentActor(Instant expires) {
        if (expires == null || !now().isBefore(expires)) throw new PublicationFailure(401, "UNAUTHENTICATED");
    }

    public Binding resource(UUID actor, Instant expires, UUID publication, UUID attempt, boolean teacher) {
        return transaction(() -> binding(actor, expires, publication, attempt, teacher, false));
    }

    public void requirePolicy() {
        expiry(Instant.now());
    }

    private Instant expiry(Instant time) {
        var expiry = policy.expiresAt(time);
        if (expiry == null || !expiry.isAfter(time)) throw new PublicationFailure(503, "SENSITIVE_POLICY_PENDING");
        return expiry;
    }

    public Ack append(
            UUID actor,
            Instant expires,
            UUID publication,
            UUID attempt,
            Binding authorized,
            long authorityUntil,
            AnswerObservation observation) {
        return transaction(() -> {
            var b = binding(actor, expires, publication, attempt, false, true);
            fresh(b, authorized, authorityUntil);
            Instant time = now();
            Instant expiry = expiry(time);
            var payload = db.queryForObject(
                    "SELECT payload::text FROM publication_snapshots WHERE publication_id=?",
                    String.class,
                    publication);
            var questions = json.readTree(payload).path("structuredContent").path("questions");
            if (observation.questionNumber() > questions.size()) throw AnswerObservation.invalid();
            observation.validate(questions.get(observation.questionNumber() - 1));
            var replay = db.queryForList(
                    "SELECT * FROM assessment_answer_audit WHERE attempt_id=? AND (event_id=? OR (stream_id=? AND sequence=?))",
                    attempt,
                    observation.eventId(),
                    observation.streamId(),
                    observation.sequence());
            if (!replay.isEmpty()) {
                if (replay.size() != 1) throw new PublicationFailure(409, "EVENT_CONFLICT");
                var row = replay.getFirst();
                if (!row.get("event_id").equals(observation.eventId())
                        || !row.get("actor_id").equals(actor)
                        || !row.get("source").equals("CLIENT_OBSERVATION")
                        || !Objects.equals(row.get("stream_id"), observation.streamId())
                        || ((Number) row.get("sequence")).longValue() != observation.sequence()
                        || !row.get("event_type").equals(observation.type())
                        || ((Number) row.get("question_number")).intValue() != observation.questionNumber()
                        || !((Timestamp) row.get("expires_at")).toInstant().isAfter(time))
                    throw new PublicationFailure(409, "EVENT_CONFLICT");
                byte[] canonical = encryption.decrypt(
                        row.get("key_id").toString(), observation.context(publication, attempt, actor), (byte[])
                                row.get("protected_input"));
                if (!java.security.MessageDigest.isEqual(canonical, observation.canonical()))
                    throw new PublicationFailure(409, "EVENT_CONFLICT");
                currentActor(expires);
                fresh(b, authorized, authorityUntil);
                return new Ack(
                        observation.eventId(),
                        row.get("ordinal").toString(),
                        ((Timestamp) row.get("received_at")).toInstant());
            }
            if (!b.state().equals("ACTIVE")) throw new PublicationFailure(409, "ATTEMPT_TERMINAL");
            // Serialize the actor-wide bounded rolling budget without retaining token/session keys.
            db.queryForList(
                    "SELECT pg_advisory_xact_lock(?)",
                    actor.getMostSignificantBits() ^ actor.getLeastSignificantBits());
            long actorCount = db.queryForObject(
                    "SELECT count(*) FROM assessment_answer_audit WHERE actor_id=? AND received_at>assessment_time()-interval '1 minute'",
                    Long.class,
                    actor);
            long attemptCount = db.queryForObject(
                    "SELECT count(*) FROM assessment_answer_audit WHERE attempt_id=? AND received_at>assessment_time()-interval '1 minute'",
                    Long.class,
                    attempt);
            if (actorCount >= 120 || attemptCount >= 60) throw new PublicationFailure(429, "AUDIT_RATE_LIMITED");
            time = now();
            expiry = expiry(time);
            var eligible = db.queryForObject(
                    "SELECT (a.deadline_at IS NULL OR a.deadline_at>assessment_time()) AND NOT EXISTS(SELECT FROM closure_request_members m JOIN closure_requests c ON c.id=m.closure_request_id WHERE m.attempt_id=a.id AND c.state IN ('PENDING','FINALIZING') AND m.closure_cutoff_at<=assessment_time()) FROM attempts a WHERE a.id=?",
                    Boolean.class,
                    attempt);
            if (!Boolean.TRUE.equals(eligible)) throw new PublicationFailure(409, "ATTEMPT_DEADLINE_REACHED");
            long ordinal = db.queryForObject(
                    "SELECT coalesce(max(ordinal),0)+1 FROM assessment_answer_audit WHERE attempt_id=?",
                    Long.class,
                    attempt);
            var protectedInput =
                    encryption.encrypt(observation.context(publication, attempt, actor), observation.canonical());
            db.update(
                    "INSERT INTO assessment_answer_audit(attempt_id,publication_id,event_id,ordinal,actor_id,source,event_type,stream_id,sequence,question_number,observed_at,received_at,expires_at,key_id,protected_input) VALUES(?,?,?,?,?,'CLIENT_OBSERVATION',?,?,?,?,?,?,?,?,?)",
                    attempt,
                    publication,
                    observation.eventId(),
                    ordinal,
                    actor,
                    observation.type(),
                    observation.streamId(),
                    observation.sequence(),
                    observation.questionNumber(),
                    observation.observedAt() == null ? null : Timestamp.from(observation.observedAt()),
                    Timestamp.from(time),
                    Timestamp.from(expiry),
                    protectedInput.keyId(),
                    protectedInput.bytes());
            fresh(b, authorized, authorityUntil);
            currentActor(expires);
            return new Ack(observation.eventId(), Long.toString(ordinal), time);
        });
    }

    private static void fresh(Binding current, Binding authorized, long until) {
        if (!Objects.equals(current.classroomId(), authorized.classroomId()) || System.nanoTime() >= until)
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
    }

    public Timeline timeline(
            ClosureStore.Teacher actor,
            UUID publication,
            UUID attempt,
            Binding authorized,
            long until,
            long after,
            int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw AnswerObservation.invalid();
        return transaction(() -> {
            var b = binding(actor.id(), actor.expiresAt(), publication, attempt, true, false);
            fresh(b, authorized, until);
            boolean complete = Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT FROM attempts a JOIN grading_results r ON r.id=a.result_id AND r.attempt_id=a.id WHERE a.id=? AND a.state='SUBMITTED')",
                    Boolean.class,
                    attempt));
            if (b.publicationState().equals("DRAFT") || !b.state().equals("SUBMITTED") || !complete)
                throw new PublicationFailure(409, "REVIEW_NOT_AVAILABLE");
            var time = now();
            var expires = expiry(time);
            var rows = db.queryForList(
                    "SELECT * FROM assessment_answer_audit WHERE attempt_id=? AND ordinal>? AND expires_at>? ORDER BY ordinal LIMIT ?",
                    attempt,
                    after,
                    Timestamp.from(time),
                    limit + 1);
            boolean more = rows.size() > limit;
            var items = new ArrayList<Item>();
            for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
                UUID event = (UUID) row.get("event_id");
                String type = row.get("event_type").toString();
                int number = ((Number) row.get("question_number")).intValue();
                var o = new AnswerObservation(
                        event,
                        (UUID) row.get("stream_id"),
                        ((Number) row.get("sequence")).longValue(),
                        type,
                        number,
                        row.get("observed_at") == null ? null : ((Timestamp) row.get("observed_at")).toInstant(),
                        null,
                        null);
                var canonical = json.readTree(encryption.decrypt(
                        row.get("key_id").toString(),
                        o.context(publication, attempt, (UUID) row.get("actor_id")),
                        (byte[]) row.get("protected_input")));
                Object transition = canonical.has("transition") ? canonical.path("transition") : null;
                String status = transition != null
                        ? "AVAILABLE"
                        : type.startsWith("ANSWER_") ? "UNSUPPORTED_TYPE" : "NOT_APPLICABLE";
                items.add(new Item(
                        event,
                        row.get("ordinal").toString(),
                        type,
                        row.get("source").toString(),
                        number,
                        o.observedAt(),
                        ((Timestamp) row.get("received_at")).toInstant(),
                        status,
                        transition));
            }
            boolean any = Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT FROM assessment_answer_audit WHERE attempt_id=?)", Boolean.class, attempt));
            var reasons = new ArrayList<String>();
            reasons.add("CLIENT_GAP");
            if (!any) reasons.add("LEGACY");
            if (Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT FROM assessment_answer_audit WHERE attempt_id=? AND expires_at<=?)",
                    Boolean.class,
                    attempt,
                    Timestamp.from(time)))) reasons.add("EXPIRED");
            db.update(
                    "INSERT INTO assessment_sensitive_read_receipts VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    publication,
                    attempt,
                    actor.id(),
                    "ANSWER_AUDIT",
                    Timestamp.from(time),
                    Timestamp.from(expires));
            fresh(b, authorized, until);
            currentActor(actor.expiresAt());
            return new Timeline(
                    List.copyOf(items),
                    more ? items.getLast().ordinal() : null,
                    new Coverage(!any ? "NOT_COLLECTED" : "PARTIAL", List.copyOf(reasons)));
        });
    }
}

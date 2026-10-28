package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.ClosureViews.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.infrastructure.ClosureCursor;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcClosureStore implements ClosureStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final AssessmentTransactionProperties limits;
    private final ClosureCursor cursor;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public JdbcClosureStore(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            AssessmentTransactionProperties limits,
            ClosureCursor cursor) {
        this.db = db;
        this.limits = limits;
        this.cursor = cursor;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(limits.timeoutSeconds());
    }

    private PublicationFailure error(int status, String code) {
        return new PublicationFailure(status, code);
    }

    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (PublicationFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw error(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private <T> T tx(Supplier<T> work) {
        return safe(() -> {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw error(503, "PERSISTENCE_UNAVAILABLE");
            return transaction.execute(status -> {
                db.execute("SET LOCAL lock_timeout='" + limits.lockMillis() + "ms'");
                db.execute("SET LOCAL statement_timeout='" + limits.statementMillis() + "ms'");
                var value = work.get();
                db.execute("SET CONSTRAINTS ALL IMMEDIATE");
                return value;
            });
        });
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private void expiry(Teacher actor) {
        if (!now().isBefore(actor.expiresAt())) throw error(401, "UNAUTHENTICATED");
    }

    private record Pub(UUID id, UUID owner, String state, long revision, Instant end, Instant closed) {}

    private Pub pub(Teacher actor, UUID id) {
        var rows = db.query(
                "SELECT * FROM publications WHERE id=? AND owner_id=? FOR UPDATE",
                (r, n) -> new Pub(
                        id,
                        actor.id(),
                        r.getString("state"),
                        r.getLong("revision"),
                        instant(r, "end_at"),
                        instant(r, "closed_at")),
                id,
                actor.id());
        if (rows.size() != 1) throw error(404, "PUBLICATION_NOT_FOUND");
        expiry(actor);
        return rows.getFirst();
    }

    private void revision(Pub p, Command command) {
        if (!p.state().equals("OPEN")) throw error(409, "PUBLICATION_CONFLICT");
        if (p.revision() != command.revision()) throw error(409, "REVISION_CONFLICT");
    }

    private String digest(Command c) {
        var fields = new TreeMap<String, Object>();
        fields.put("action", c.action());
        fields.put("publicationId", c.publicationId());
        fields.put("closureRequestId", c.requestId());
        fields.put("expectedRevision", c.revision());
        return JdbcPublicationStore.digest(json.writeValueAsString(fields));
    }

    private Map<String, Object> operation(Teacher actor, Command c) {
        var rows = db.queryForList(
                "SELECT * FROM publication_operations WHERE publication_id=? AND actor_id=? AND key_digest=?",
                c.publicationId(),
                actor.id(),
                c.key());
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!c.action().equals(row.get("action")) || !digest(c).equals(row.get("request_digest")))
            throw error(409, "IDEMPOTENCY_CONFLICT");
        return row;
    }

    private Reply replay(Map<String, Object> op) {
        if (op == null || !"ACKNOWLEDGED".equals(op.get("state"))) return null;
        String body = op.get("response_body").toString();
        Object projection =
                switch (op.get("action").toString()) {
                    case "CLOSE" -> json.readValue(body, Closed.class);
                    case "REQUEST", "CANCEL" -> json.readValue(body, Closure.class);
                    case "COMPLETE" -> json.readValue(body, Completion.class);
                    default -> throw error(503, "PERSISTENCE_UNAVAILABLE");
                };
        return new Reply(((Number) op.get("response_status")).intValue(), projection);
    }

    private void receipt(Teacher actor, Command c, UUID request, int status, Object body) {
        db.update(
                "INSERT INTO publication_operations(publication_id,actor_id,key_digest,action,request_digest,closure_request_id,state,response_status,response_body,created_at,acknowledged_at) VALUES(?,?,?,?,?,?,'ACKNOWLEDGED',?,?::jsonb,?,?)",
                c.publicationId(),
                actor.id(),
                c.key(),
                c.action(),
                digest(c),
                request,
                status,
                json.writeValueAsString(body),
                Timestamp.from(now()),
                Timestamp.from(now()));
    }

    private void acknowledge(Teacher actor, Command c, int status, Object body) {
        db.update(
                "UPDATE publication_operations SET state='ACKNOWLEDGED',response_status=?,response_body=?::jsonb,acknowledged_at=? WHERE publication_id=? AND actor_id=? AND key_digest=?",
                status,
                json.writeValueAsString(body),
                Timestamp.from(now()),
                c.publicationId(),
                actor.id(),
                c.key());
    }

    private void bump(UUID publication) {
        db.update("UPDATE publications SET revision=revision+1 WHERE id=?", publication);
    }

    private Closure row(ResultSet r, int index) throws SQLException {
        return new Closure(
                r.getObject("id", UUID.class),
                r.getObject("publication_id", UUID.class),
                r.getObject("requesting_teacher_id", UUID.class),
                instant(r, "requested_at"),
                r.getString("state"),
                instant(r, "finalization_started_at"),
                instant(r, "resolved_at"),
                r.getLong("publication_revision"));
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        var t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private Closure request(UUID publication, UUID request) {
        var rows = db.query(
                "SELECT c.*,p.revision AS publication_revision FROM closure_requests c JOIN publications p ON p.id=c.publication_id WHERE c.id=? AND c.publication_id=? FOR UPDATE OF c",
                this::row,
                request,
                publication);
        if (rows.size() != 1) throw error(404, "CLOSURE_NOT_FOUND");
        return rows.getFirst();
    }

    private void live(Closure c) {
        if (!Set.of("PENDING", "FINALIZING").contains(c.state())) throw error(409, "CLOSURE_RESOLVED");
    }

    @Override
    public Reply request(Teacher actor, Command c) {
        return tx(() -> {
            Pub p = pub(actor, c.publicationId());
            var old = replay(operation(actor, c));
            if (old != null) return old;
            revision(p, c);
            Instant time = now();
            if (time.isBefore(p.end())) throw error(409, "CLOSURE_NOT_READY");
            if (Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM closure_requests WHERE publication_id=? AND state IN ('PENDING','FINALIZING'))",
                    Boolean.class,
                    p.id()))) throw error(409, "CLOSURE_PENDING");
            UUID id = UUID.randomUUID();
            db.update(
                    "INSERT INTO closure_requests(id,publication_id,requesting_teacher_id,requested_at,state) VALUES(?,?,?,?,'PENDING')",
                    id,
                    p.id(),
                    actor.id(),
                    Timestamp.from(time));
            // One INSERT SELECT and deferred initial-set fingerprint: capacity failure rolls back everything.
            db.update(
                    "INSERT INTO closure_request_members(closure_request_id,attempt_id,publication_id,student_id,state) SELECT ?,id,publication_id,student_id,'NOTICE_PENDING' FROM attempts WHERE publication_id=? AND state='ACTIVE' AND duration_minutes=0 ORDER BY id",
                    id,
                    p.id());
            bump(p.id());
            var body = request(p.id(), id);
            receipt(actor, c, id, 201, body);
            return new Reply(201, body);
        });
    }

    @Override
    public Reply cancel(Teacher actor, Command c) {
        return tx(() -> {
            var p = pub(actor, c.publicationId());
            var old = replay(operation(actor, c));
            if (old != null) return old;
            revision(p, c);
            var request = request(p.id(), c.requestId());
            live(request);
            if (request.finalizationStartedAt() != null) throw error(409, "CLOSURE_FINALIZATION_STARTED");
            var time = now();
            db.update(
                    "UPDATE closure_requests SET state='CANCELLED',resolved_at=? WHERE id=?",
                    Timestamp.from(time),
                    request.closureRequestId());
            db.query(
                    "SELECT id FROM attempts WHERE id IN (SELECT attempt_id FROM closure_request_members WHERE closure_request_id=?) ORDER BY id FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    request.closureRequestId());
            db.update(
                    "UPDATE closure_request_members SET state='RESOLVED_CANCELLED',resolved_at=? WHERE closure_request_id=? AND state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')",
                    Timestamp.from(time),
                    request.closureRequestId());
            bump(p.id());
            var body = request(p.id(), c.requestId());
            receipt(actor, c, c.requestId(), 200, body);
            return new Reply(200, body);
        });
    }

    @Override
    public Closure get(Teacher actor, UUID publication, UUID request) {
        return tx(() -> {
            pub(actor, publication);
            return request(publication, request);
        });
    }

    @Override
    public Reply preflight(Teacher actor, Command c) {
        return tx(() -> {
            var p = pub(actor, c.publicationId());
            var old = replay(operation(actor, c));
            if (old != null) return old;
            revision(p, c);
            return null;
        });
    }

    @Override
    public Optional<UUID> probeRecipient(Teacher actor, UUID publication) {
        return tx(() -> {
            pub(actor, publication);
            return db
                    .query(
                            "SELECT student_id FROM attempts WHERE publication_id=? AND state='ACTIVE' AND duration_minutes=0 ORDER BY id LIMIT 1",
                            (r, n) -> r.getObject(1, UUID.class),
                            publication)
                    .stream()
                    .findFirst();
        });
    }

    @Override
    public Operation reserve(Teacher actor, Command c, int budget) {
        return tx(() -> {
            var p = pub(actor, c.publicationId());
            var op = operation(actor, c);
            var old = replay(op);
            if (old != null) return new Operation(true, old, 0, 0);
            if (op != null) {
                live(request(p.id(), c.requestId()));
                return new Operation(
                        false,
                        null,
                        ((Number) op.get("processed_units")).intValue(),
                        ((Number) op.get("unit_budget")).intValue());
            }
            revision(p, c);
            live(request(p.id(), c.requestId()));
            db.update(
                    "INSERT INTO publication_operations(publication_id,actor_id,key_digest,action,request_digest,closure_request_id,state,unit_budget,created_at) VALUES(?,?,?,'COMPLETE',?,?,'IN_PROGRESS',?,?)",
                    p.id(),
                    actor.id(),
                    c.key(),
                    digest(c),
                    c.requestId(),
                    budget,
                    Timestamp.from(now()));
            return new Operation(false, null, 0, budget);
        });
    }

    @Override
    public Optional<UUID> due(UUID publication, UUID request) {
        return safe(() -> {
            var bounded = db.query(
                    "SELECT id FROM attempts WHERE publication_id=? AND state='ACTIVE' AND deadline_at<=assessment_time() ORDER BY deadline_at,id LIMIT 1",
                    (r, n) -> r.getObject(1, UUID.class),
                    publication);
            if (!bounded.isEmpty()) return Optional.of(bounded.getFirst());
            if (request == null) return Optional.empty();
            return db
                    .query(
                            "SELECT m.attempt_id FROM closure_request_members m JOIN closure_requests c ON c.id=m.closure_request_id JOIN attempts a ON a.id=m.attempt_id WHERE c.id=? AND c.publication_id=? AND c.state IN ('PENDING','FINALIZING') AND m.state='NOTICE_CONFIRMED' AND a.state='ACTIVE' AND m.closure_cutoff_at<=assessment_time() ORDER BY m.closure_cutoff_at,m.attempt_id LIMIT 1",
                            (r, n) -> r.getObject(1, UUID.class),
                            request,
                            publication)
                    .stream()
                    .findFirst();
        });
    }

    private boolean active(UUID publication) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM attempts WHERE publication_id=? AND state='ACTIVE')",
                Boolean.class,
                publication));
    }

    private void resolve(UUID publication, UUID request, String state, Instant time) {
        if (request == null) return;
        db.update("UPDATE closure_requests SET state=?,resolved_at=? WHERE id=?", state, Timestamp.from(time), request);
        db.update(
                "UPDATE closure_request_members SET state='RESOLVED_TERMINAL',resolved_at=? WHERE closure_request_id=? AND state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')",
                Timestamp.from(time),
                request);
    }

    private void closed(UUID id, Instant time) {
        db.update(
                "UPDATE publications SET state='CLOSED',closed_at=?,revision=revision+1 WHERE id=?",
                Timestamp.from(time),
                id);
    }

    @Override
    public Reply close(Teacher actor, Command c) {
        return tx(() -> {
            var p = pub(actor, c.publicationId());
            var old = replay(operation(actor, c));
            if (old != null) return old;
            revision(p, c);
            var requests = db.query(
                    "SELECT id FROM closure_requests WHERE publication_id=? AND state IN ('PENDING','FINALIZING') ORDER BY id FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    p.id());
            if (active(p.id())) throw error(409, "ACTIVE_ATTEMPTS");
            var time = now();
            expiry(actor);
            for (var request : requests) resolve(p.id(), request, "RESOLVED_EARLY", time);
            closed(p.id(), time);
            var body = new Closed(p.id(), "CLOSED", p.revision() + 1, time);
            receipt(actor, c, null, 200, body);
            return new Reply(200, body);
        });
    }

    @Override
    public Reply finish(Teacher actor, Command c) {
        return tx(() -> {
            var p = pub(actor, c.publicationId());
            var op = operation(actor, c);
            var old = replay(op);
            if (old != null) return old;
            if (op == null) throw error(409, "IDEMPOTENCY_CONFLICT");
            var request = request(p.id(), c.requestId());
            live(request);
            revision(p, c);
            if (!active(p.id())) {
                var time = now();
                expiry(actor);
                resolve(p.id(), c.requestId(), "COMPLETED", time);
                closed(p.id(), time);
                var body = new Completion(c.requestId(), p.id(), true, p.revision() + 1);
                acknowledge(actor, c, 200, body);
                return new Reply(200, body);
            }
            // A 202 means committed budget exhaustion and more eligible work, never a hidden 48h bypass.
            int used = ((Number) op.get("processed_units")).intValue(),
                    budget = ((Number) op.get("unit_budget")).intValue();
            if (used >= budget && due(p.id(), c.requestId()).isPresent()) {
                var body = new Completion(c.requestId(), p.id(), false, p.revision());
                acknowledge(actor, c, 202, body);
                return new Reply(202, body);
            }
            if (due(p.id(), c.requestId()).isPresent()) throw error(503, "PERSISTENCE_UNAVAILABLE");
            throw error(409, "CLOSURE_NOT_READY");
        });
    }

    @Override
    public List<Work> candidates(int limit) {
        return safe(() -> db.query(
                "SELECT m.publication_id,m.closure_request_id,m.attempt_id,m.student_id,m.state,m.next_work_at AS work_at FROM closure_request_members m JOIN closure_requests c ON c.id=m.closure_request_id WHERE c.state IN ('PENDING','FINALIZING') AND (m.state IN ('NOTICE_PENDING','NOTICE_PUBLISHED') AND m.next_work_at<=assessment_time() OR m.state='NOTICE_CONFIRMED' AND m.closure_cutoff_at<=assessment_time()) UNION ALL SELECT a.publication_id,c.id,a.id,a.student_id,'BOUNDED_TIMEOUT',a.deadline_at FROM attempts a JOIN closure_requests c ON c.publication_id=a.publication_id WHERE c.state IN ('PENDING','FINALIZING') AND a.state='ACTIVE' AND a.deadline_at<=assessment_time() ORDER BY work_at,closure_request_id,attempt_id LIMIT ?",
                (r, n) -> new Work(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class),
                        r.getString(5)),
                limit));
    }

    private Map<String, Object> work(Work work) {
        if (db.query(
                        "SELECT id FROM publications WHERE id=? AND state='OPEN' FOR UPDATE SKIP LOCKED",
                        (r, n) -> r.getObject(1, UUID.class),
                        work.publicationId())
                .isEmpty()) return null;
        var requests = db.queryForList(
                "SELECT * FROM closure_requests WHERE id=? AND publication_id=? AND state IN ('PENDING','FINALIZING') FOR UPDATE",
                work.requestId(),
                work.publicationId());
        if (requests.isEmpty()) return null;
        var attempts = db.queryForList(
                "SELECT state FROM attempts WHERE id=? AND publication_id=? AND student_id=? FOR UPDATE",
                work.attemptId(),
                work.publicationId(),
                work.recipientId());
        if (attempts.isEmpty() || !"ACTIVE".equals(attempts.getFirst().get("state"))) return null;
        var members = db.queryForList(
                "SELECT * FROM closure_request_members WHERE closure_request_id=? AND attempt_id=? AND student_id=? FOR UPDATE",
                work.requestId(),
                work.attemptId(),
                work.recipientId());
        return members.isEmpty() ? null : members.getFirst();
    }

    public static final String WARNING =
            "Your Teacher requested closure of this assessment. You have 48 full hours after confirmed access to this notice to save, submit or abandon this Attempt. At the displayed cutoff, your latest saved answers will be submitted and graded automatically. Unsaved browser changes cannot be included. The Publication closes only after a Teacher completes closure.";

    @Override
    public boolean publish(Work work) {
        return tx(() -> {
            var member = work(work);
            if (member == null || !"NOTICE_PENDING".equals(member.get("state"))) return false;
            UUID id = UUID.randomUUID();
            Instant time = now();
            db.update(
                    "INSERT INTO assessment_notifications(id,closure_request_id,attempt_id,recipient_id,content_version,message,publication_title,state,published_at) SELECT ?,?,?,?,1,?,configuration->>'title','PUBLISHED',? FROM publications WHERE id=?",
                    id,
                    work.requestId(),
                    work.attemptId(),
                    work.recipientId(),
                    WARNING,
                    Timestamp.from(time),
                    work.publicationId());
            db.update(
                    "UPDATE closure_request_members SET state='NOTICE_PUBLISHED',notification_id=? WHERE closure_request_id=? AND attempt_id=?",
                    id,
                    work.requestId(),
                    work.attemptId());
            return true;
        });
    }

    @Override
    public boolean confirm(Work work, ClosureDependencies.Eligibility eligibility) {
        if (eligibility == null
                || !eligibility.eligible()
                || !work.recipientId().equals(eligibility.studentId())) throw error(503, "DEPENDENCY_UNAVAILABLE");
        Instant checkedAt = eligibility.checkedAt();
        return tx(() -> {
            var member = work(work);
            if (member == null || !"NOTICE_PUBLISHED".equals(member.get("state"))) return false;
            // The exact owner-bound inbox projection is checked again in the authoritative transaction.
            notification(work.recipientId(), (UUID) member.get("notification_id"));
            var time = now();
            if (System.nanoTime() - eligibility.validUntilNanos() >= 0
                    || checkedAt == null
                    || checkedAt.isAfter(time)
                    || checkedAt.plusSeconds(5).isBefore(time)) throw error(503, "DEPENDENCY_UNAVAILABLE");
            db.update(
                    "UPDATE closure_request_members SET state='NOTICE_CONFIRMED',confirmed_notice_at=?,closure_cutoff_at=?,accessibility_version=1,recipient_checked_at=? WHERE closure_request_id=? AND attempt_id=?",
                    Timestamp.from(time),
                    Timestamp.from(time.plusSeconds(172800)),
                    Timestamp.from(checkedAt),
                    work.requestId(),
                    work.attemptId());
            return true;
        });
    }

    private static final String NOTIFICATION =
            "SELECT n.*,m.publication_id,m.confirmed_notice_at,m.closure_cutoff_at,m.state AS member_state,c.state AS request_state,a.state AS attempt_state,a.result_id,assessment_time() AS server_now FROM assessment_notifications n JOIN closure_request_members m ON m.closure_request_id=n.closure_request_id AND m.attempt_id=n.attempt_id AND m.student_id=n.recipient_id JOIN closure_requests c ON c.id=m.closure_request_id JOIN attempts a ON a.id=m.attempt_id";

    private Notification notificationRow(ResultSet r, int index) throws SQLException {
        String status = "PREPARING",
                request = r.getString("request_state"),
                member = r.getString("member_state"),
                attempt = r.getString("attempt_state");
        Instant time = instant(r, "server_now"), cutoff = instant(r, "closure_cutoff_at");
        if (request.equals("CANCELLED")) status = "CANCELLED";
        else if (member.equals("FINALIZED")) status = "FINALIZED";
        else if (attempt.equals("SUBMITTED")) status = "SUBMITTED";
        else if (attempt.equals("ABANDONED")) status = "ABANDONED";
        else if (request.equals("RESOLVED_EARLY")) status = "RESOLVED_EARLY";
        else if (cutoff != null) status = time.isBefore(cutoff) ? "NOTICE_CONFIRMED" : "CUTOFF_REACHED";
        return new Notification(
                r.getObject("id", UUID.class),
                r.getObject("publication_id", UUID.class),
                r.getString("publication_title"),
                r.getObject("attempt_id", UUID.class),
                r.getObject("closure_request_id", UUID.class),
                true,
                1,
                instant(r, "published_at"),
                instant(r, "confirmed_notice_at"),
                cutoff,
                48,
                status,
                r.getString("message"),
                r.getObject("result_id", UUID.class),
                time);
    }

    @Override
    public Notification published(Work work) {
        return safe(() -> {
            var ids = db.query(
                    "SELECT notification_id FROM closure_request_members WHERE closure_request_id=? AND attempt_id=? AND publication_id=? AND student_id=? AND notification_id IS NOT NULL",
                    (r, n) -> r.getObject(1, UUID.class),
                    work.requestId(),
                    work.attemptId(),
                    work.publicationId(),
                    work.recipientId());
            if (ids.isEmpty()) throw error(404, "NOTIFICATION_NOT_FOUND");
            return notification(work.recipientId(), ids.getFirst());
        });
    }

    @Override
    public Notification notification(UUID actor, UUID id) {
        return safe(() -> {
            var rows = db.query(NOTIFICATION + " WHERE n.recipient_id=? AND n.id=?", this::notificationRow, actor, id);
            if (rows.isEmpty()) throw error(404, "NOTIFICATION_NOT_FOUND");
            return rows.getFirst();
        });
    }

    private void limit(int limit) {
        if (limit < 1 || limit > 100) throw error(400, "INVALID_REQUEST");
    }

    @Override
    public Page<Notification> inbox(UUID actor, int limit, String token) {
        return safe(() -> {
            limit(limit);
            String position = cursor.decode(token, actor, "inbox", "notifications");
            var args = new ArrayList<Object>();
            args.add(actor);
            String where = " WHERE n.recipient_id=?";
            if (position != null) {
                try {
                    var fields = position.split(",", -1);
                    if (fields.length != 2) throw new IllegalArgumentException();
                    var time = Instant.parse(fields[0]);
                    var id = UUID.fromString(fields[1]);
                    where += " AND (n.published_at,n.id)<(?,?)";
                    args.add(Timestamp.from(time));
                    args.add(id);
                } catch (RuntimeException e) {
                    throw error(400, "INVALID_REQUEST");
                }
            }
            args.add(limit + 1);
            var rows = db.query(
                    NOTIFICATION + where + " ORDER BY n.published_at DESC,n.id DESC LIMIT ?",
                    this::notificationRow,
                    args.toArray());
            boolean more = rows.size() > limit;
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            var last = page.isEmpty() ? null : page.getLast();
            return new Page<>(
                    page,
                    more
                            ? cursor.encode(
                                    actor, "inbox", "notifications", last.publishedAt() + "," + last.notificationId())
                            : null);
        });
    }

    @Override
    public Page<TeacherRow> results(Teacher actor, UUID publication, int limit, String token) {
        return tx(() -> {
            pub(actor, publication);
            limit(limit);
            String position = cursor.decode(token, actor.id(), "results", publication.toString());
            UUID after = null;
            if (position != null) {
                try {
                    after = UUID.fromString(position);
                } catch (RuntimeException e) {
                    throw error(400, "INVALID_REQUEST");
                }
            }
            var args = new ArrayList<Object>();
            args.add(publication);
            if (after != null) args.add(after);
            args.add(limit + 1);
            var rows = db.query(
                    "SELECT a.*,r.earned_weight,r.possible_weight FROM attempts a LEFT JOIN grading_results r ON r.id=a.result_id WHERE a.publication_id=?"
                            + (after == null ? "" : " AND a.id>?") + " ORDER BY a.id LIMIT ?",
                    (r, n) -> {
                        var id = r.getObject("id", UUID.class);
                        String ordinal = Long.toString(r.getLong("ordinal"));
                        var resultId = r.getObject("result_id", UUID.class);
                        AttemptViews.Result result = null;
                        if (resultId != null) {
                            var earned = r.getBigDecimal("earned_weight");
                            var possible = r.getBigDecimal("possible_weight");
                            result = new AttemptViews.Result(
                                    resultId,
                                    id,
                                    publication,
                                    ordinal,
                                    GradingPolicy.decimal(earned),
                                    GradingPolicy.decimal(possible),
                                    GradingPolicy.percentage(earned, possible),
                                    r.getInt("grading_policy_version"),
                                    "QUIZOPIA_GRADING_V1",
                                    r.getString("terminal_reason"),
                                    instant(r, "finalized_at"));
                        }
                        return new TeacherRow(
                                id, r.getObject("student_id", UUID.class), ordinal, r.getString("state"), result, null);
                    },
                    args.toArray());
            boolean more = rows.size() > limit;
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new Page<>(
                    page,
                    more
                            ? cursor.encode(
                                    actor.id(),
                                    "results",
                                    publication.toString(),
                                    page.getLast().attemptId().toString())
                            : null);
        });
    }

    @Override
    public void workOutcome(Work work, String outcome) {
        tx(() -> {
            var publications = db.query(
                    "SELECT id FROM publications WHERE id=? FOR UPDATE SKIP LOCKED",
                    (r, n) -> r.getObject(1, UUID.class),
                    work.publicationId());
            if (publications.isEmpty()) return false;
            var requests = db.query(
                    "SELECT id FROM closure_requests WHERE id=? AND publication_id=? FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    work.requestId(),
                    work.publicationId());
            if (requests.isEmpty()) return false;
            var attempts = db.query(
                    "SELECT id FROM attempts WHERE id=? AND publication_id=? AND student_id=? FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    work.attemptId(),
                    work.publicationId(),
                    work.recipientId());
            if (attempts.isEmpty()) return false;
            db.update(
                    "INSERT INTO closure_work_audit(closure_request_id,attempt_id,stage,outcome) VALUES(?,?,?,?)",
                    work.requestId(),
                    work.attemptId(),
                    work.stage(),
                    outcome);
            boolean failed = Set.of("INELIGIBLE", "AUTHORITY_UNAVAILABLE", "INBOX_UNAVAILABLE", "FAILED")
                    .contains(outcome);
            db.update(
                    "UPDATE closure_request_members SET next_work_at=assessment_time()+(?*interval '1 second'),retry_count=retry_count+? WHERE closure_request_id=? AND attempt_id=? AND state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')",
                    failed ? 60 : 0,
                    failed ? 1 : 0,
                    work.requestId(),
                    work.attemptId());
            return true;
        });
    }

    @Override
    public Map<String, Double> backlog() {
        return safe(() -> {
            var row = db.queryForMap(
                    "SELECT count(*) FILTER(WHERE m.state IN ('NOTICE_PENDING','NOTICE_PUBLISHED')) AS unconfirmed,coalesce(extract(epoch FROM assessment_time()-min(c.requested_at) FILTER(WHERE m.state IN ('NOTICE_PENDING','NOTICE_PUBLISHED'))),0) AS oldest,count(*) FILTER(WHERE m.state='NOTICE_CONFIRMED' AND m.closure_cutoff_at<=assessment_time()) AS eligible FROM closure_request_members m JOIN closure_requests c ON c.id=m.closure_request_id WHERE c.state IN ('PENDING','FINALIZING')");
            return Map.of(
                    "unconfirmed",
                    ((Number) row.get("unconfirmed")).doubleValue(),
                    "oldest_seconds",
                    ((Number) row.get("oldest")).doubleValue(),
                    "eligible",
                    ((Number) row.get("eligible")).doubleValue());
        });
    }
}

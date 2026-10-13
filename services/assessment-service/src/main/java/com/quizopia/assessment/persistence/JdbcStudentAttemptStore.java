package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.AttemptViews.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.infrastructure.GradingDocuments;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcStudentAttemptStore implements StudentAttemptStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final AssessmentTransactionProperties limits;
    private final AttemptFinalization finalizer;
    private final GradingDocuments documents = new GradingDocuments();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public JdbcStudentAttemptStore(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            AssessmentTransactionProperties limits,
            AttemptFinalization finalizer) {
        this.db = db;
        this.limits = limits;
        this.finalizer = finalizer;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(limits.timeoutSeconds());
    }

    private static PublicationFailure error(int status, String code) {
        return new PublicationFailure(status, code);
    }

    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (PublicationFailure e) {
            throw e;
        } catch (GradingPolicy.Failure e) {
            throw error(e.unavailable() ? 503 : 400, e.getMessage());
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
                T value = work.get();
                db.execute("SET CONSTRAINTS ALL IMMEDIATE");
                return value;
            });
        });
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private Instant authorizedNow(Actor actor) {
        Instant time = now();
        if (!time.isBefore(actor.expiresAt())) throw error(401, "UNAUTHENTICATED");
        return time;
    }

    private record Pub(
            UUID id, PublicationConfiguration configuration, String state, UUID snapshot, String locator, int max) {
        public String toString() {
            return "Pub[redacted]";
        }
    }

    private Pub pub(UUID id, boolean lock) {
        var rows = db.query(
                "SELECT * FROM publications WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Pub(
                        id,
                        json.readValue(r.getString("configuration"), PublicationConfiguration.class),
                        r.getString("state"),
                        r.getObject("snapshot_id", UUID.class),
                        r.getString("private_locator"),
                        r.getInt("max_attempts")),
                id);
        if (rows.isEmpty()) throw error(404, "PUBLICATION_NOT_FOUND");
        return rows.getFirst();
    }

    @Override
    public Resource publication(UUID id) {
        return safe(() -> {
            var p = pub(id, false);
            // Audience/classroom are immutable only after OPEN. Never authorize against
            // a mutable DRAFT and then act on a subsequently opened different audience.
            if (p.state().equals("DRAFT")) throw error(404, "PUBLICATION_NOT_FOUND");
            return new Resource(id, p.configuration());
        });
    }

    private record Attempt(
            UUID id,
            UUID publication,
            long ordinal,
            String state,
            long revision,
            Instant started,
            Instant deadline,
            int duration) {}

    private Attempt row(ResultSet r, int n) throws SQLException {
        return new Attempt(
                r.getObject("id", UUID.class),
                r.getObject("publication_id", UUID.class),
                r.getLong("ordinal"),
                r.getString("state"),
                r.getLong("revision"),
                r.getTimestamp("started_at").toInstant(),
                r.getTimestamp("deadline_at") == null
                        ? null
                        : r.getTimestamp("deadline_at").toInstant(),
                r.getInt("duration_minutes"));
    }

    private Attempt attempt(Actor actor, UUID id, boolean lock) {
        var found = db.query(
                "SELECT * FROM attempts WHERE id=? AND student_id=?" + (lock ? " FOR UPDATE" : ""),
                this::row,
                id,
                actor.id());
        if (found.isEmpty()) throw error(404, "ATTEMPT_NOT_FOUND");
        return found.getFirst();
    }

    @Override
    public Resource owned(Actor actor, UUID id) {
        return safe(() -> {
            var a = attempt(actor, id, false);
            return publication(a.publication());
        });
    }

    private Attempt lock(Actor actor, UUID id) {
        var a = attempt(actor, id, false);
        pub(a.publication(), true);
        return attempt(actor, id, true);
    }

    private boolean due(Attempt a, Instant time) {
        return a.state().equals("ACTIVE") && JdbcAttemptFinalization.due(a.deadline(), time);
    }

    private record Protected(JsonNode root, GradingPolicy.Snapshot grading) {
        public String toString() {
            return "Protected[redacted]";
        }
    }

    private Protected snapshot(Pub p) {
        return db.queryForObject(
                "SELECT payload::text,schema_version,grading_policy_version FROM publication_snapshots WHERE publication_id=?",
                (r, n) -> {
                    String raw = r.getString(1);
                    var grading = documents.snapshot(
                            raw.getBytes(StandardCharsets.UTF_8), p.configuration(), r.getInt(2), r.getInt(3));
                    return new Protected(documents.tree(raw), grading);
                },
                p.snapshot());
    }

    private List<Question> questions(Protected s) {
        var result = new ArrayList<Question>();
        for (var q : s.root().path("structuredContent").path("questions")) {
            var options = new ArrayList<Option>();
            for (var o : q.path("options"))
                options.add(new Option(
                        o.path("label").asString(), o.path("markdown").asString()));
            result.add(new Question(
                    q.path("number").asInt(),
                    q.path("type").asString(),
                    q.path("stemMarkdown").asString(),
                    options));
        }
        return List.copyOf(result);
    }

    private Answer answer(GradingPolicy.Answer a) {
        return switch (a) {
            case GradingPolicy.Single v -> new Single(a.type().name(), v.label());
            case GradingPolicy.Multiple v -> new Multiple(a.type().name(), v.labels());
            case GradingPolicy.Matrix v -> {
                var m = new TreeMap<String, Boolean>();
                for (int i = 0; i < 4; i++)
                    m.put("" + (char) ('A' + i), v.judgments().get(i));
                yield new Matrix(a.type().name(), m);
            }
            case GradingPolicy.Numeric v -> new Numeric(a.type().name(), v.token());
        };
    }

    private List<GradingPolicy.Answer> saved(Attempt a, Protected s) {
        var values = new ArrayList<GradingPolicy.Answer>();
        for (var q : s.grading().questions())
            values.add(GradingPolicy.unanswered(q.key().type()));
        db.query(
                "SELECT question_number,answer::text FROM attempt_answers WHERE attempt_id=? ORDER BY question_number",
                r -> {
                    int i = r.getInt(1) - 1;
                    if (i < 0 || i >= values.size()) throw GradingPolicy.unavailable();
                    values.set(
                            i,
                            documents.answer(
                                    documents.tree(r.getString(2)),
                                    s.grading().questions().get(i)));
                },
                a.id());
        return List.copyOf(values);
    }

    private View view(Actor actor, Attempt a, Pub p) {
        var s = snapshot(p);
        var answers = saved(a, s);
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < answers.size(); i++) entries.add(new Entry(i + 1, answer(answers.get(i))));
        return new View(
                a.id(),
                p.id(),
                Long.toString(a.ordinal()),
                a.state(),
                a.started(),
                a.deadline(),
                a.duration(),
                Long.toString(a.revision()),
                authorizedNow(actor),
                questions(s),
                entries,
                null);
    }

    private String digest(Map<String, Object> request) {
        return JdbcPublicationStore.digest(json.writeValueAsString(new TreeMap<>(request)));
    }

    private void locator(Pub p, String locator) {
        if (p.configuration().audience() == PublicationConfiguration.Audience.CLASS) {
            if (locator != null) throw error(400, "INVALID_REQUEST");
        } else if (locator == null || !locator.matches("[A-Za-z0-9_-]{43}")) throw error(400, "INVALID_REQUEST");
        else if (!locator.equals(p.locator())) throw error(404, "PUBLICATION_NOT_FOUND");
    }
    /** Ensure a complete canonical answer set is representable within the initial HTTP budget. */
    private void capacity(Protected s) {
        var largest = new ArrayList<GradingPolicy.Answer>();
        for (var q : s.grading().questions())
            largest.add(
                    switch (q.key().type()) {
                        case SINGLE_CHOICE -> new GradingPolicy.Single(null);
                        case MULTIPLE_CHOICE -> new GradingPolicy.Multiple(List.of("A", "B", "C", "D"));
                        case TRUE_FALSE_MATRIX -> new GradingPolicy.Matrix(List.of(false, false, false, false));
                        case NUMERIC_FILL -> new GradingPolicy.Numeric("0000");
                    });
        // Includes the longest signed-bigint revision and fixed request envelope.
        if (documents.canonical(largest).getBytes(StandardCharsets.UTF_8).length + 80 > 2097152)
            throw error(503, "DEPENDENCY_UNAVAILABLE");
    }

    @Override
    public Step<View> start(Actor actor, UUID id, String action, String locator, String key) {
        return tx(() -> {
            Pub p = pub(id, true);
            locator(p, locator);
            authorizedNow(actor);
            var request = new TreeMap<String, Object>();
            request.put("action", action);
            request.put("publicationId", id.toString());
            request.put("locator", locator);
            String hash = digest(request);
            var receipts = db.queryForList(
                    "SELECT action,request_digest,attempt_id FROM attempt_creation_operations WHERE publication_id=? AND student_id=? AND key_digest=?",
                    id,
                    actor.id(),
                    key);
            if (!receipts.isEmpty()) {
                var receipt = receipts.getFirst();
                if (!action.equals(receipt.get("action")) || !hash.equals(receipt.get("request_digest")))
                    throw error(409, "IDEMPOTENCY_CONFLICT");
                Attempt a = attempt(actor, (UUID) receipt.get("attempt_id"), true);
                if (due(a, authorizedNow(actor))) return Step.due(a.id());
                var v = view(actor, a, p);
                if (due(a, v.serverNow())) return Step.due(a.id());
                return Step.done(v);
            }
            var history = db.query(
                    "SELECT * FROM attempts WHERE publication_id=? AND student_id=? ORDER BY ordinal DESC LIMIT 1 FOR UPDATE",
                    this::row,
                    id,
                    actor.id());
            Attempt latest = history.isEmpty() ? null : history.getFirst();
            if (latest != null && due(latest, authorizedNow(actor))) return Step.due(latest.id());
            if (action.equals("START") && latest != null) {
                var v = view(actor, latest, p);
                if (due(latest, v.serverNow())) return Step.due(latest.id());
                db.update(
                        "INSERT INTO attempt_creation_operations VALUES(?,?,?,?,?,?,?)",
                        id,
                        actor.id(),
                        key,
                        action,
                        hash,
                        latest.id(),
                        Timestamp.from(v.serverNow()));
                return Step.done(v);
            }
            if (action.equals("RETAKE")) {
                if (latest == null) throw error(409, "RETAKE_NOT_AVAILABLE");
                if (latest.state().equals("ACTIVE")) throw error(409, "ACTIVE_ATTEMPT_EXISTS");
            }
            if (!p.state().equals("OPEN")) throw error(409, "PUBLICATION_CONFLICT");
            if (p.max() != 0 && (latest == null ? 0 : latest.ordinal()) >= p.max())
                throw error(409, "ATTEMPTS_EXHAUSTED");
            var s = snapshot(p);
            capacity(s);
            Instant time = authorizedNow(actor);
            if (time.isBefore(p.configuration().startAt())) throw error(409, "WINDOW_NOT_STARTED");
            if (!time.isBefore(p.configuration().endAt())) throw error(409, "WINDOW_ENDED");
            long ordinal = latest == null ? 1 : Math.addExact(latest.ordinal(), 1);
            UUID attemptId = UUID.randomUUID();
            int duration = p.configuration().durationMinutes();
            Instant deadline = duration == 0 ? null : time.plusSeconds(duration * 60L);
            db.update(
                    "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',?,?,?)",
                    attemptId,
                    id,
                    actor.id(),
                    ordinal,
                    p.snapshot(),
                    duration,
                    Timestamp.from(time),
                    deadline == null ? null : Timestamp.from(deadline));
            db.update(
                    "INSERT INTO attempt_creation_operations VALUES(?,?,?,?,?,?,?)",
                    id,
                    actor.id(),
                    key,
                    action,
                    hash,
                    attemptId,
                    Timestamp.from(time));
            var a = attempt(actor, attemptId, false);
            return new Step<>(view(actor, a, p), null, true);
        });
    }

    @Override
    public Step<View> resume(Actor actor, UUID id) {
        return tx(() -> {
            Attempt a = lock(actor, id);
            if (due(a, authorizedNow(actor))) return Step.due(id);
            var v = view(actor, a, pub(a.publication(), false));
            if (due(a, v.serverNow())) return Step.due(id);
            return Step.done(v);
        });
    }

    private record Receipt(String action, String digest, String response) {}

    private Receipt receipt(UUID id, String key) {
        return db
                .query(
                        "SELECT action,request_digest,response_body::text FROM attempt_operations WHERE attempt_id=? AND key_digest=?",
                        (r, n) -> new Receipt(r.getString(1), r.getString(2), r.getString(3)),
                        id,
                        key)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void matches(Receipt r, String action, String hash) {
        if (!r.action().equals(action) || !r.digest().equals(hash)) throw error(409, "IDEMPOTENCY_CONFLICT");
    }

    @Override
    public Step<SaveAck> save(Actor actor, UUID id, int question, long revision, String key, String raw) {
        return tx(() -> {
            Attempt a = lock(actor, id);
            authorizedNow(actor);
            var s = snapshot(pub(a.publication(), false));
            if (question < 1 || question > s.grading().questions().size() || revision < 0)
                throw error(400, "INVALID_REQUEST");
            var value = documents.answer(
                    documents.tree(raw), s.grading().questions().get(question - 1));
            var request = new TreeMap<String, Object>();
            request.put("action", "SAVE");
            request.put("attemptId", id.toString());
            request.put("expectedRevision", Long.toString(revision));
            request.put("questionNumber", question);
            request.put("answer", documents.tree(documents.answerJson(value)));
            String hash = digest(request);
            var receipt = receipt(id, key);
            if (receipt != null) {
                matches(receipt, "SAVE", hash);
                authorizedNow(actor);
                return Step.done(json.readValue(receipt.response(), SaveAck.class));
            }
            if (!a.state().equals("ACTIVE")) throw error(409, "ATTEMPT_TERMINAL");
            Instant time = authorizedNow(actor);
            if (due(a, time)) return Step.due(id);
            if (a.revision() != revision) throw error(409, "REVISION_CONFLICT");
            long next = Math.addExact(revision, 1);
            db.update("UPDATE attempts SET revision=? WHERE id=?", next, id);
            db.update(
                    "INSERT INTO attempt_answers VALUES(?,?,?::jsonb,?,?) ON CONFLICT(attempt_id,question_number) DO UPDATE SET answer=EXCLUDED.answer,saved_revision=EXCLUDED.saved_revision,saved_at=EXCLUDED.saved_at",
                    id,
                    question,
                    documents.answerJson(value),
                    next,
                    Timestamp.from(time));
            var ack = new SaveAck(id, question, Long.toString(next), time);
            db.update(
                    "INSERT INTO attempt_operations VALUES(?,?,'SAVE',?,?,?,?,200,?::jsonb)",
                    id,
                    key,
                    hash,
                    question,
                    next,
                    Timestamp.from(time),
                    json.writeValueAsString(ack));
            return Step.done(ack);
        });
    }

    @Override
    public Step<AbandonAck> abandon(Actor actor, UUID id, long revision, String key) {
        return tx(() -> {
            Attempt a = lock(actor, id);
            authorizedNow(actor);
            String hash = digest(Map.of(
                    "action", "ABANDON", "attemptId", id.toString(), "expectedRevision", Long.toString(revision)));
            var receipt = receipt(id, key);
            if (receipt != null) {
                matches(receipt, "ABANDON", hash);
                return Step.done(json.readValue(receipt.response(), AbandonAck.class));
            }
            if (!a.state().equals("ACTIVE")) throw error(409, "ATTEMPT_TERMINAL");
            Instant time = authorizedNow(actor);
            if (due(a, time)) return Step.due(id);
            if (a.revision() != revision) throw error(409, "REVISION_CONFLICT");
            long next = Math.addExact(revision, 1);
            db.update(
                    "UPDATE attempts SET state='ABANDONED',revision=?,terminal_reason='STUDENT_ABANDON',finalized_at=? WHERE id=?",
                    next,
                    Timestamp.from(time),
                    id);
            db.update(
                    "INSERT INTO attempt_finalization_audit(attempt_id,publication_id,reason,actor_kind,actor_id,finalized_at,grading_policy_version) VALUES(?,?,'STUDENT_ABANDON','USER',?,?,1)",
                    id,
                    a.publication(),
                    actor.id().toString(),
                    Timestamp.from(time));
            var ack = new AbandonAck(id, "ABANDONED", "STUDENT_ABANDON", Long.toString(next), time);
            db.update(
                    "INSERT INTO attempt_operations(attempt_id,key_digest,action,request_digest,accepted_revision,acknowledged_at,response_status,response_body) VALUES(?,?,'ABANDON',?,?,?,200,?::jsonb)",
                    id,
                    key,
                    hash,
                    next,
                    Timestamp.from(time),
                    json.writeValueAsString(ack));
            return Step.done(ack);
        });
    }

    private List<Attempt> history(Actor actor, UUID id) {
        return db.query(
                "SELECT * FROM attempts WHERE publication_id=? AND student_id=? ORDER BY ordinal DESC LIMIT 1",
                this::row,
                id,
                actor.id());
    }

    private Result resultValue(Attempt a, AttemptFinalization.Result r) {
        return new Result(
                r.resultId(),
                a.id(),
                a.publication(),
                Long.toString(a.ordinal()),
                r.earnedWeight(),
                r.possibleWeight(),
                r.percentageDisplay(),
                r.gradingPolicyVersion(),
                "QUIZOPIA_GRADING_V1",
                r.reason().name(),
                r.finalizedAt());
    }

    private Result requireResult(Actor actor, Attempt a) {
        return resultValue(
                a,
                finalizer
                        .result(new AttemptFinalization.SelectedAttempt(a.id(), actor.id(), actor.expiresAt()))
                        .orElseThrow(() -> error(409, "RESULT_NOT_AVAILABLE")));
    }

    @Override
    public Step<Result> result(Actor actor, UUID id) {
        return tx(() -> {
            var a = lock(actor, id);
            if (due(a, authorizedNow(actor))) return Step.due(id);
            return Step.done(requireResult(actor, a));
        });
    }

    @Override
    public Step<Aggregate> aggregate(Actor actor, UUID id) {
        return tx(() -> {
            pub(id, true);
            Instant time = authorizedNow(actor);
            var rows = history(actor, id);
            if (rows.isEmpty()) throw error(404, "PUBLICATION_NOT_FOUND");
            if (due(rows.getFirst(), time)) return Step.due(rows.getFirst().id());
            var best = finalizer.best(id, actor.id());
            return Step.done(new Aggregate(
                    id,
                    best.map(r -> resultValue(attempt(actor, r.attemptId(), false), r))
                            .orElse(null)));
        });
    }
    /** Pure review gate also tested without manufacturing forbidden CLOSED database state. */
    public static void requireReview(String publicationState, String attemptState, boolean resultPresent) {
        com.quizopia.assessment.infrastructure.ReviewProjection.require(publicationState, attemptState, resultPresent);
    }

    @Override
    public Step<Review> review(Actor actor, UUID id) {
        return tx(() -> {
            Attempt a = lock(actor, id);
            if (due(a, authorizedNow(actor))) return Step.due(id);
            Pub p = pub(a.publication(), false);
            var r = finalizer.result(new AttemptFinalization.SelectedAttempt(id, actor.id(), actor.expiresAt()));
            requireReview(p.state(), a.state(), r.isPresent());
            var s = snapshot(p);
            var questions = questions(s);
            var answers = saved(a, s);
            var projectedAnswers = new ArrayList<Answer>();
            var keys = new ArrayList<Answer>();
            var earned = new ArrayList<String>();
            var explanations = new ArrayList<String>();
            var grades = db.queryForList(
                    "SELECT question_number,earned_weight FROM grading_question_results WHERE result_id=? ORDER BY question_number",
                    r.orElseThrow().resultId());
            if (grades.size() != questions.size()) throw GradingPolicy.unavailable();
            for (int i = 0; i < questions.size(); i++) {
                if (((Number) grades.get(i).get("question_number")).intValue() != i + 1)
                    throw GradingPolicy.unavailable();
                var explanation = s.root()
                        .path("structuredContent")
                        .path("questions")
                        .get(i)
                        .path("explanationMarkdown");
                projectedAnswers.add(answer(answers.get(i)));
                keys.add(answer(s.grading().questions().get(i).key()));
                earned.add(GradingPolicy.decimal(
                        (java.math.BigDecimal) grades.get(i).get("earned_weight")));
                explanations.add(explanation.isNull() ? null : explanation.asString());
            }
            return Step.done(com.quizopia.assessment.infrastructure.ReviewProjection.project(
                    p.state(),
                    a.state(),
                    resultValue(a, r.orElseThrow()),
                    questions,
                    projectedAnswers,
                    keys,
                    earned,
                    explanations));
        });
    }
}

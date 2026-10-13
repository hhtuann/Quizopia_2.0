package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.attempt.AttemptRecord;
import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.domain.grading.GradingPolicy.*;
import com.quizopia.assessment.infrastructure.GradingDocuments;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcAttemptFinalization implements AttemptFinalization {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final GradingDocuments documents = new GradingDocuments();
    private final GradingPolicy grader = new GradingPolicy();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public JdbcAttemptFinalization(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private static PublicationFailure failure(int status, String code) {
        return new PublicationFailure(status, code);
    }

    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (PublicationFailure e) {
            throw e;
        } catch (GradingPolicy.Failure e) {
            throw failure(e.unavailable() ? 503 : 400, e.getMessage());
        } catch (RuntimeException e) {
            throw failure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private <T> T tx(Supplier<T> work) {
        return safe(() -> {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw failure(503, "FINALIZATION_TRANSACTION_REQUIRED");
            return transaction.execute(status -> {
                db.execute("SET LOCAL lock_timeout='3s'");
                db.execute("SET LOCAL statement_timeout='3s'");
                return work.get();
            });
        });
    }

    private record Locked(
            UUID id,
            UUID publication,
            UUID student,
            long revision,
            String state,
            Instant deadline,
            UUID snapshot,
            int policy,
            int answerSchema,
            PublicationConfiguration configuration) {
        public String toString() {
            return "Locked[redacted]";
        }
    }

    private Locked lock(UUID id, UUID student) {
        var publications = db.query(
                "SELECT publication_id FROM attempts WHERE id=?" + (student == null ? "" : " AND student_id=?"),
                (r, n) -> r.getObject(1, UUID.class),
                student == null ? new Object[] {id} : new Object[] {id, student});
        if (publications.size() != 1) throw failure(404, "ATTEMPT_NOT_FOUND");
        String config = db.queryForObject(
                "SELECT configuration::text FROM publications WHERE id=? FOR UPDATE",
                String.class,
                publications.getFirst());
        var locked = db.query(
                "SELECT * FROM attempts WHERE id=? FOR UPDATE",
                (r, n) -> new Locked(
                        id,
                        r.getObject("publication_id", UUID.class),
                        r.getObject("student_id", UUID.class),
                        r.getLong("revision"),
                        r.getString("state"),
                        r.getTimestamp("deadline_at") == null
                                ? null
                                : r.getTimestamp("deadline_at").toInstant(),
                        r.getObject("snapshot_id", UUID.class),
                        r.getInt("grading_policy_version"),
                        r.getInt("answer_schema_version"),
                        json.readValue(config, PublicationConfiguration.class)),
                id);
        if (locked.size() != 1
                || student != null && !locked.getFirst().student().equals(student))
            throw failure(404, "ATTEMPT_NOT_FOUND");
        return locked.getFirst();
    }

    private Snapshot snapshot(Locked a) {
        if (a.answerSchema() != 1) throw GradingPolicy.unavailable();
        return db.queryForObject(
                "SELECT payload::text,schema_version,grading_policy_version FROM publication_snapshots WHERE publication_id=?",
                (r, n) -> {
                    if (r.getInt(3) != a.policy()) throw GradingPolicy.unavailable();
                    return documents.snapshot(
                            r.getString(1).getBytes(StandardCharsets.UTF_8),
                            a.configuration(),
                            r.getInt(2),
                            a.policy());
                },
                a.snapshot());
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    public static boolean due(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }

    private record AnswerSet(List<Answer> values, Set<Integer> normalizedQuestions) {
        public String toString() {
            return "AnswerSet[redacted]";
        }
    }

    private AnswerSet saved(Locked a, Snapshot snapshot) {
        try {
            var answers = new ArrayList<Answer>();
            var normalized = new HashSet<Integer>();
            for (var q : snapshot.questions())
                answers.add(GradingPolicy.unanswered(q.key().type()));
            db.query(
                    "SELECT question_number,answer::text FROM attempt_answers WHERE attempt_id=? ORDER BY question_number",
                    r -> {
                        int q = r.getInt(1);
                        if (q < 1 || q > answers.size()) throw GradingPolicy.unavailable();
                        var raw = documents.tree(r.getString(2));
                        var answer = documents.answer(raw, snapshot.questions().get(q - 1));
                        // V4 permits equivalent multiple-selection array orders. Normalize that
                        // representation in the terminal transaction, retaining the real saved_at.
                        if (!documents.tree(documents.answerJson(answer)).equals(raw)) normalized.add(q);
                        answers.set(q - 1, answer);
                    },
                    a.id());
            return new AnswerSet(List.copyOf(answers), Set.copyOf(normalized));
        } catch (GradingPolicy.Failure e) {
            throw GradingPolicy.unavailable();
        }
    }

    private String requestDigest(Manual request, List<Answer> replacement) {
        var value = new TreeMap<String, Object>();
        value.put("action", "SUBMIT");
        value.put("attemptId", request.selected().attemptId().toString());
        value.put("expectedRevision", Long.toString(request.expectedRevision()));
        value.put("answers", replacement == null ? null : documents.tree(documents.canonical(replacement)));
        return JdbcPublicationStore.digest(json.writeValueAsString(value));
    }

    @Override
    public Outcome submit(Manual request) {
        if (request == null
                || request.selected() == null
                || request.expectedRevision() < 0
                || request.keyDigest() == null
                || !request.keyDigest().matches("[0-9a-f]{64}")) throw failure(400, "INVALID_REQUEST");
        return tx(() -> {
            Locked a = lock(request.selected().attemptId(), request.selected().studentId());
            Snapshot snapshot = snapshot(a);
            List<Answer> replacement = request.replacementJson() == null
                    ? null
                    : documents.replacement(request.replacementJson(), snapshot);
            String digest = requestDigest(request, replacement);
            var receipts = db.queryForList(
                    "SELECT action,request_digest FROM attempt_operations WHERE attempt_id=? AND key_digest=?",
                    a.id(),
                    request.keyDigest());
            if (!receipts.isEmpty()) {
                var receipt = receipts.getFirst();
                if (!"SUBMIT".equals(receipt.get("action")) || !digest.equals(receipt.get("request_digest")))
                    throw failure(409, "IDEMPOTENCY_CONFLICT");
                return new Outcome(Kind.REPLAY, Optional.of(requireResult(a.id())));
            }
            if (!a.state().equals("ACTIVE")) throw failure(409, "ATTEMPT_TERMINAL");
            // This is the linearization sample, after locks and full payload validation.
            Instant time = now();
            if (due(a.deadline(), time))
                return new Outcome(
                        Kind.DEADLINE_REACHED,
                        Optional.of(persist(
                                a,
                                snapshot,
                                saved(a, snapshot),
                                null,
                                time,
                                AttemptRecord.Reason.BOUNDED_TIMEOUT,
                                null,
                                null)));
            if (a.revision() != request.expectedRevision()) throw failure(409, "REVISION_CONFLICT");
            AnswerSet answers = replacement == null ? saved(a, snapshot) : new AnswerSet(replacement, Set.of());
            return new Outcome(
                    Kind.SUBMITTED,
                    Optional.of(persist(
                            a,
                            snapshot,
                            answers,
                            replacement,
                            time,
                            AttemptRecord.Reason.MANUAL_SUBMIT,
                            request.keyDigest(),
                            digest)));
        });
    }

    @Override
    public Outcome systemFinalize(UUID id, AttemptRecord.Reason reason) {
        if (reason != AttemptRecord.Reason.BOUNDED_TIMEOUT) throw failure(503, "FINALIZATION_NOT_AVAILABLE");
        if (id == null) throw failure(400, "INVALID_REQUEST");
        return tx(() -> {
            Locked a = lock(id, null);
            if (!a.state().equals("ACTIVE")) return new Outcome(Kind.ALREADY_TERMINAL, readResult(id));
            Snapshot snapshot = snapshot(a);
            Instant time = now();
            if (a.deadline() == null) return new Outcome(Kind.NOT_APPLICABLE, Optional.empty());
            if (!due(a.deadline(), time)) return new Outcome(Kind.NOT_DUE, Optional.empty());
            return new Outcome(
                    Kind.SUBMITTED,
                    Optional.of(persist(a, snapshot, saved(a, snapshot), null, time, reason, null, null)));
        });
    }

    private Result persist(
            Locked a,
            Snapshot snapshot,
            AnswerSet finalAnswers,
            List<Answer> replacement,
            Instant time,
            AttemptRecord.Reason reason,
            String key,
            String requestDigest) {
        List<Answer> answers = finalAnswers.values();
        Score score = grader.grade(snapshot, answers);
        long revision = Math.addExact(a.revision(), 1);
        UUID submitted = UUID.randomUUID(), result = UUID.randomUUID();
        if (replacement != null) {
            var rows = new ArrayList<Object[]>();
            for (int i = 0; i < replacement.size(); i++)
                rows.add(new Object[] {
                    a.id(), i + 1, documents.answerJson(replacement.get(i)), revision, Timestamp.from(time)
                });
            db.batchUpdate(
                    "INSERT INTO attempt_answers(attempt_id,question_number,answer,saved_revision,saved_at) VALUES(?,?,?::jsonb,?,?) ON CONFLICT(attempt_id,question_number) DO UPDATE SET answer=excluded.answer,saved_revision=excluded.saved_revision,saved_at=excluded.saved_at",
                    rows);
        }
        if (replacement == null) {
            for (int q : finalAnswers.normalizedQuestions()) {
                db.update(
                        "UPDATE attempt_answers SET answer=?::jsonb,saved_revision=? WHERE attempt_id=? AND question_number=?",
                        documents.answerJson(answers.get(q - 1)),
                        revision,
                        a.id(),
                        q);
            }
        }
        String canonical = documents.canonical(answers), digest = JdbcPublicationStore.digest(canonical);
        db.update(
                "INSERT INTO submitted_answers VALUES(?,?,1,?::jsonb,?,?)",
                submitted,
                a.id(),
                canonical,
                digest,
                Timestamp.from(time));
        db.update(
                "INSERT INTO grading_results VALUES(?,?,?,?,?,?,?,?)",
                result,
                a.id(),
                a.snapshot(),
                a.policy(),
                submitted,
                score.earned(),
                score.possible(),
                Timestamp.from(time));
        var weights = new ArrayList<Object[]>();
        for (int i = 0; i < score.questionWeights().size(); i++)
            weights.add(new Object[] {result, i + 1, score.questionWeights().get(i)});
        db.batchUpdate(
                "INSERT INTO grading_question_results(result_id,question_number,earned_weight,possible_weight) VALUES(?,?,?,1)",
                weights);
        db.update(
                "INSERT INTO attempt_finalization_audit(attempt_id,publication_id,reason,actor_kind,actor_id,finalized_at,grading_policy_version,result_id) VALUES(?,?,?,?,?,?,?,?)",
                a.id(),
                a.publication(),
                reason.name(),
                reason == AttemptRecord.Reason.MANUAL_SUBMIT ? "USER" : "SYSTEM",
                reason == AttemptRecord.Reason.MANUAL_SUBMIT ? a.student().toString() : "assessment-timeout",
                Timestamp.from(time),
                a.policy(),
                result);
        if (db.update(
                        "UPDATE attempts SET state='SUBMITTED',terminal_reason=?,finalized_at=?,revision=?,submitted_answers_id=?,result_id=? WHERE id=? AND state='ACTIVE' AND revision=?",
                        reason.name(),
                        Timestamp.from(time),
                        revision,
                        submitted,
                        result,
                        a.id(),
                        a.revision())
                != 1) throw failure(503, "PERSISTENCE_UNAVAILABLE");
        Result summary = new Result(
                a.id(),
                result,
                submitted,
                a.policy(),
                revision,
                reason,
                time,
                GradingPolicy.decimal(score.earned()),
                GradingPolicy.decimal(score.possible()),
                GradingPolicy.percentage(score.earned(), score.possible()));
        if (key != null) {
            // Persist only exact totals and identity. Rounded display is presentation, never storage.
            var receipt = new TreeMap<String, Object>();
            receipt.put("attemptId", a.id().toString());
            receipt.put("resultId", result.toString());
            receipt.put("submittedAnswersId", submitted.toString());
            receipt.put("gradingPolicyVersion", a.policy());
            receipt.put("revision", Long.toString(revision));
            receipt.put("reason", reason.name());
            receipt.put("finalizedAt", time.toString());
            receipt.put("earnedWeight", summary.earnedWeight());
            receipt.put("possibleWeight", summary.possibleWeight());
            db.update(
                    "INSERT INTO attempt_operations(attempt_id,key_digest,action,request_digest,accepted_revision,acknowledged_at,response_status,response_body) VALUES(?,?,'SUBMIT',?,?,?,200,?::jsonb)",
                    a.id(),
                    key,
                    requestDigest,
                    revision,
                    Timestamp.from(time),
                    json.writeValueAsString(receipt));
        }
        return summary;
    }

    private static final String RESULT =
            "SELECT a.id AS selected_attempt,a.revision,a.terminal_reason,a.finalized_at,r.* FROM attempts a JOIN grading_results r ON r.id=a.result_id AND r.attempt_id=a.id WHERE a.state='SUBMITTED'";

    private Result resultRow(ResultSet r, int n) throws SQLException {
        var earned = r.getBigDecimal("earned_weight");
        var possible = r.getBigDecimal("possible_weight");
        return new Result(
                r.getObject("selected_attempt", UUID.class),
                r.getObject("id", UUID.class),
                r.getObject("submitted_answers_id", UUID.class),
                r.getInt("grading_policy_version"),
                r.getLong("revision"),
                AttemptRecord.Reason.valueOf(r.getString("terminal_reason")),
                r.getTimestamp("finalized_at").toInstant(),
                GradingPolicy.decimal(earned),
                GradingPolicy.decimal(possible),
                GradingPolicy.percentage(earned, possible));
    }

    private Optional<Result> readResult(UUID id) {
        return db.query(RESULT + " AND a.id=?", this::resultRow, id).stream().findFirst();
    }

    private Result requireResult(UUID id) {
        return readResult(id).orElseThrow(() -> failure(503, "GRADING_UNAVAILABLE"));
    }

    @Override
    public Optional<Result> result(SelectedAttempt selected) {
        return safe(() -> db
                .query(
                        RESULT + " AND a.id=? AND a.student_id=?",
                        this::resultRow,
                        selected.attemptId(),
                        selected.studentId())
                .stream()
                .findFirst());
    }

    @Override
    public Optional<Result> best(UUID publicationId, UUID studentId) {
        return safe(() -> db
                .query(
                        RESULT
                                + " AND a.publication_id=? AND a.student_id=? ORDER BY r.earned_weight DESC,a.ordinal,r.id LIMIT 1",
                        this::resultRow,
                        publicationId,
                        studentId)
                .stream()
                .findFirst());
    }
}

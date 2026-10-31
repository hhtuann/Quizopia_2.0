package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.TeacherAssessmentStore.*;
import com.quizopia.assessment.application.TeacherAssessmentViews.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.grading.GradingPolicy;
import com.quizopia.assessment.infrastructure.ClosureCursor;
import com.quizopia.assessment.infrastructure.GradingDocuments;
import java.math.*;
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

/** Whole-publication SQL aggregation, live keyset pages and separate submitted-only review.
 * Every local transaction rechecks ownership/fence under the same publication lock as erasure. */
@Repository
public class TeacherAssessmentQueries implements TeacherAssessmentStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AssessmentTransactionProperties limits;
    private final ClosureCursor cursors;
    private final SensitiveAssessmentPolicy sensitive;
    private final ThreadLocal<Long> authorityDeadline = new ThreadLocal<>();
    private final ThreadLocal<Resource> authorizedResource = new ThreadLocal<>();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public TeacherAssessmentQueries(
            @org.springframework.context.annotation.Lazy JdbcTemplate db,
            @org.springframework.context.annotation.Lazy PlatformTransactionManager manager,
            AssessmentTransactionProperties limits,
            ClosureCursor cursors,
            SensitiveAssessmentPolicy sensitive) {
        this.db = db;
        this.limits = limits;
        this.cursors = cursors;
        this.sensitive = sensitive;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(limits.timeoutSeconds());
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private static PublicationFailure invalid() {
        return new PublicationFailure(400, "INVALID_REQUEST");
    }

    private static PublicationFailure unavailable() {
        return new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
    }

    public <T> T withAuthorityDeadline(long deadline, Resource resource, Supplier<T> work) {
        authorityDeadline.set(deadline);
        authorizedResource.set(resource);
        try {
            return work.get();
        } finally {
            authorityDeadline.remove();
            authorizedResource.remove();
        }
    }

    private void fresh(ClosureStore.Teacher actor) {
        if (actor.expiresAt() == null || !now().isBefore(actor.expiresAt()))
            throw new PublicationFailure(401, "UNAUTHENTICATED");
        var deadline = authorityDeadline.get();
        if (deadline != null && System.nanoTime() >= deadline)
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
            return tx.execute(status -> {
                db.execute("SET LOCAL lock_timeout='" + limits.lockMillis() + "ms'");
                db.execute("SET LOCAL statement_timeout='" + limits.statementMillis() + "ms'");
                var value = work.get();
                db.execute("SET CONSTRAINTS ALL IMMEDIATE");
                return value;
            });
        } catch (PublicationFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private Resource owned(ClosureStore.Teacher actor, UUID publication) {
        var rows = db.query(
                "SELECT id,classroom_id,state,quiz_id FROM publications WHERE id=? AND owner_id=? FOR SHARE",
                (r, n) -> new Resource(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getString(3),
                        r.getObject(4, UUID.class)),
                publication,
                actor.id());
        if (rows.size() != 1) throw new PublicationFailure(404, "PUBLICATION_NOT_FOUND");
        // READ COMMITTED external fence probe would not be sufficient without the shared row lock.
        new QuizRevocationGate(db).requireQuizOpen(rows.getFirst().quizId());
        var authorized = authorizedResource.get();
        if (authorized != null
                && !Objects.equals(authorized.classroomId(), rows.getFirst().classroomId()))
            throw new PublicationFailure(409, "PUBLICATION_CONFLICT");
        fresh(actor);
        return rows.getFirst();
    }

    public Resource resource(ClosureStore.Teacher actor, UUID publication) {
        return transaction(() -> owned(actor, publication));
    }

    private static final String RANKED =
            """
        WITH ranked AS (
          SELECT a.*,r.id AS grade_id,r.earned_weight,r.possible_weight,
            row_number() OVER (PARTITION BY a.student_id ORDER BY a.ordinal DESC,a.id) AS latest_rank,
            row_number() OVER (PARTITION BY a.student_id ORDER BY (r.id IS NULL),r.earned_weight DESC NULLS LAST,a.ordinal,r.id) AS best_rank,
            row_number() OVER (PARTITION BY a.student_id ORDER BY (r.id IS NULL),a.ordinal DESC,a.id) AS graded_rank
          FROM attempts a LEFT JOIN grading_results r ON a.result_id=r.id AND r.attempt_id=a.id AND a.state='SUBMITTED'
          WHERE a.publication_id=?
        ), grouped AS (
          SELECT student_id,count(*) AS attempt_count,count(grade_id) AS graded_count,
            bool_or(state='ACTIVE') AS has_active,bool_or(state='SUBMITTED') AS has_submitted,
            bool_or(state='ABANDONED') AS has_abandoned,
            sum(earned_weight) AS sum_earned,sum(possible_weight) AS sum_possible
          FROM ranked GROUP BY student_id
        )
        """;

    public Overview overview(ClosureStore.Teacher actor, UUID publication) {
        return transaction(() -> {
            owned(actor, publication);
            var stats = db.queryForMap(
                    RANKED
                            + """
                SELECT count(*) FILTER(WHERE g.has_active) AS active,
                  count(*) FILTER(WHERE g.has_submitted) AS submitted,count(*) AS participated,
                  count(b.grade_id) AS graded,max(b.earned_weight) AS best_earned,max(b.possible_weight) AS possible,
                  sum(b.earned_weight) AS sum_earned,sum(b.possible_weight) AS sum_possible
                FROM grouped g JOIN ranked b ON b.student_id=g.student_id AND b.best_rank=1
                """,
                    publication);
            var bins = new long[20];
            db.query(
                    RANKED
                            + """
                SELECT CASE WHEN earned_weight=0 THEN 0 ELSE div(20*earned_weight*4-1,possible_weight*4)::integer END AS bin,
                  count(*) AS n FROM ranked WHERE best_rank=1 AND grade_id IS NOT NULL GROUP BY bin
                """,
                    r -> {
                        int bin = r.getInt("bin");
                        if (bin < 0 || bin > 19) throw unavailable();
                        bins[bin] = r.getLong("n");
                    },
                    publication);
            var distribution = new ArrayList<Bin>();
            for (int i = 0; i < 20; i++)
                distribution.add(new Bin(
                        i,
                        decimal(BigDecimal.valueOf(i).divide(BigDecimal.TWO)),
                        decimal(BigDecimal.valueOf(i + 1).divide(BigDecimal.TWO)),
                        i == 0,
                        true,
                        Long.toString(bins[i])));
            long graded = ((Number) stats.get("graded")).longValue();
            if (Arrays.stream(bins).sum() != graded) throw unavailable();
            fresh(actor);
            return new Overview(
                    publication,
                    now(),
                    stats.get("active").toString(),
                    stats.get("submitted").toString(),
                    stats.get("participated").toString(),
                    Long.toString(graded),
                    graded == 0
                            ? null
                            : score((BigDecimal) stats.get("best_earned"), (BigDecimal) stats.get("possible")),
                    mean((BigDecimal) stats.get("sum_earned"), (BigDecimal) stats.get("sum_possible"), graded),
                    List.copyOf(distribution));
        });
    }

    public static int bin(BigDecimal earned, BigDecimal possible) {
        validate(earned, possible);
        return earned.signum() == 0
                ? 0
                : earned.multiply(BigDecimal.valueOf(20))
                                .divide(possible, 0, RoundingMode.CEILING)
                                .intValueExact()
                        - 1;
    }

    public static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void validate(BigDecimal earned, BigDecimal possible) {
        if (earned == null
                || possible == null
                || possible.signum() <= 0
                || earned.signum() < 0
                || earned.compareTo(possible) > 0) throw new PublicationFailure(503, "GRADING_UNAVAILABLE");
    }

    private static String display(BigDecimal earned, BigDecimal possible) {
        validate(earned, possible);
        return earned.multiply(BigDecimal.TEN)
                .divide(possible, 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static Score score(BigDecimal earned, BigDecimal possible) {
        return new Score(decimal(earned), decimal(possible), display(earned, possible));
    }

    private static Mean mean(BigDecimal earned, BigDecimal possible, long count) {
        return count == 0
                ? null
                : new Mean(decimal(earned), decimal(possible), Long.toString(count), display(earned, possible));
    }

    private AttemptViews.Result result(ResultSet r) throws SQLException {
        if (r.getObject("grade_id") == null) return null;
        var earned = r.getBigDecimal("earned_weight");
        var possible = r.getBigDecimal("possible_weight");
        validate(earned, possible);
        return new AttemptViews.Result(
                r.getObject("grade_id", UUID.class),
                r.getObject("id", UUID.class),
                r.getObject("publication_id", UUID.class),
                Long.toString(r.getLong("ordinal")),
                decimal(earned),
                decimal(possible),
                earned.multiply(BigDecimal.valueOf(100))
                        .divide(possible, 2, RoundingMode.HALF_UP)
                        .toPlainString(),
                1,
                GradingPolicy.NAME,
                r.getString("terminal_reason"),
                instant(r, "finalized_at"));
    }

    private static Instant instant(ResultSet r, String field) throws SQLException {
        var t = r.getTimestamp(field);
        return t == null ? null : t.toInstant();
    }

    private Attempt attempt(ResultSet r) throws SQLException {
        var result = result(r);
        return new Attempt(
                r.getObject("id", UUID.class),
                Long.toString(r.getLong("ordinal")),
                r.getString("state"),
                instant(r, "started_at"),
                instant(r, "deadline_at"),
                instant(r, "finalized_at"),
                r.getString("terminal_reason"),
                result,
                "SUBMITTED".equals(r.getString("state")) && result != null && sensitive.disclosureAvailable());
    }

    private static final String JOINS =
            """
        SELECT a.*,r.id AS grade_id,r.earned_weight,r.possible_weight FROM attempts a
        LEFT JOIN grading_results r ON a.result_id=r.id AND r.attempt_id=a.id AND a.state='SUBMITTED'
        """;

    public Students students(ClosureStore.Teacher actor, UUID publication, Filter filter) {
        return transaction(() -> {
            owned(actor, publication);
            String position =
                    cursors.decode(filter.cursor(), actor.id(), "teacher-students-v1", filter.binding(publication));
            var args = new ArrayList<Object>();
            args.add(publication);
            var conditions = new ArrayList<String>();
            if (filter.studentId() != null) {
                conditions.add("g.student_id=?");
                args.add(filter.studentId());
            }
            if (filter.status() != null)
                conditions.add(
                        switch (filter.status()) {
                            case "ACTIVE" -> "g.has_active";
                            case "SUBMITTED" -> "g.has_submitted";
                            default -> "g.has_abandoned";
                        });
            String value =
                    filter.sort().equals("LATEST") ? "extract(epoch FROM l.started_at)*1000000" : "b.earned_weight";
            String order = filter.direction();
            if (position != null) {
                var fields = position.split("~", -1);
                if (fields.length != 2) throw invalid();
                UUID student;
                BigDecimal last;
                try {
                    student = UUID.fromString(fields[1]);
                    last = fields[0].equals("null") ? null : new BigDecimal(fields[0]);
                } catch (RuntimeException e) {
                    throw invalid();
                }
                String op = order.equals("ASC") ? ">" : "<";
                if (last == null) {
                    conditions.add(value + " IS NULL AND g.student_id>?");
                    args.add(student);
                } else {
                    conditions.add(
                            "(" + value + " IS NULL OR " + value + op + "? OR (" + value + "=? AND g.student_id>?))");
                    args.add(last);
                    args.add(last);
                    args.add(student);
                }
            }
            String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
            args.add(filter.limit() + 1);
            var selected = db.query(
                    RANKED
                            + """
                SELECT g.student_id,g.attempt_count,g.graded_count,g.has_active,g.has_submitted,g.sum_earned,g.sum_possible,
                  l.id AS latest_id,b.id AS best_id,lg.id AS graded_id,
                """
                            + value
                            + " AS sort_value FROM grouped g JOIN ranked l ON l.student_id=g.student_id AND l.latest_rank=1 "
                            + "JOIN ranked b ON b.student_id=g.student_id AND b.best_rank=1 "
                            + "JOIN ranked lg ON lg.student_id=g.student_id AND lg.graded_rank=1" + where
                            + " ORDER BY " + value + " " + order + " NULLS LAST,g.student_id ASC LIMIT ?",
                    (r, n) -> new Selection(
                            r.getObject("student_id", UUID.class),
                            r.getLong("attempt_count"),
                            r.getLong("graded_count"),
                            r.getBoolean("has_active"),
                            r.getBoolean("has_submitted"),
                            r.getBigDecimal("sum_earned"),
                            r.getBigDecimal("sum_possible"),
                            r.getObject("latest_id", UUID.class),
                            r.getObject("best_id", UUID.class),
                            r.getObject("graded_id", UUID.class),
                            r.getBigDecimal("sort_value")),
                    args.toArray());
            boolean more = selected.size() > filter.limit();
            var page = selected.subList(0, Math.min(filter.limit(), selected.size()));
            // One bounded SQL batch for all three Attempt references per selected student; no N+1.
            var ids = new LinkedHashSet<UUID>();
            page.forEach(s -> {
                ids.add(s.latest());
                ids.add(s.best());
                ids.add(s.graded());
            });
            var references = new HashMap<UUID, Attempt>();
            if (!ids.isEmpty()) {
                var values = new ArrayList<Object>();
                values.add(publication);
                values.addAll(ids);
                db.query(
                        JOINS + " WHERE a.publication_id=? AND a.id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
                        r -> {
                            var a = attempt(r);
                            references.put(a.attemptId(), a);
                        },
                        values.toArray());
            }
            var items = page.stream()
                    .map(s -> new Student(
                            s.student(),
                            null,
                            Long.toString(s.attempts()),
                            Long.toString(s.grades()),
                            s.active(),
                            s.submitted(),
                            references.get(s.latest()),
                            references.get(s.graded()).result(),
                            references.get(s.best()).result(),
                            mean(s.earned(), s.possible(), s.grades())))
                    .toList();
            String next = null;
            if (more) {
                var last = page.getLast();
                next = cursors.encode(
                        actor.id(),
                        "teacher-students-v1",
                        filter.binding(publication),
                        (last.value() == null ? "null" : decimal(last.value())) + "~" + last.student());
            }
            fresh(actor);
            return new Students(publication, now(), items, next);
        });
    }

    private record Selection(
            UUID student,
            long attempts,
            long grades,
            boolean active,
            boolean submitted,
            BigDecimal earned,
            BigDecimal possible,
            UUID latest,
            UUID best,
            UUID graded,
            BigDecimal value) {}

    public History history(ClosureStore.Teacher actor, UUID publication, UUID student, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw invalid();
        return transaction(() -> {
            owned(actor, publication);
            String binding = publication + ":" + student;
            String position = cursors.decode(cursor, actor.id(), "teacher-history-v1", binding);
            var args = new ArrayList<Object>(List.of(publication, student));
            String after = "";
            if (position != null) {
                try {
                    long last = Long.parseLong(position);
                    if (last < 1) throw invalid();
                    args.add(last);
                    after = " AND a.ordinal<?";
                } catch (NumberFormatException e) {
                    throw invalid();
                }
            }
            args.add(limit + 1);
            var rows = db.query(
                    JOINS + " WHERE a.publication_id=? AND a.student_id=?" + after
                            + " ORDER BY a.ordinal DESC,a.id LIMIT ?",
                    (r, n) -> attempt(r),
                    args.toArray());
            if (rows.isEmpty() && position == null) throw new PublicationFailure(404, "ATTEMPT_NOT_FOUND");
            boolean more = rows.size() > limit;
            var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            String next = more
                    ? cursors.encode(
                            actor.id(),
                            "teacher-history-v1",
                            binding,
                            items.getLast().ordinal())
                    : null;
            fresh(actor);
            return new History(publication, student, now(), items, next);
        });
    }

    public Review review(ClosureStore.Teacher actor, UUID publication, UUID attemptId) {
        return transaction(() -> {
            sensitive.requireActor(actor.id());
            var resource = owned(actor, publication);
            var rows = db.query(
                    JOINS + " WHERE a.publication_id=? AND a.id=?", (r, n) -> attempt(r), publication, attemptId);
            if (rows.size() != 1) throw new PublicationFailure(404, "ATTEMPT_NOT_FOUND");
            if (sensitive.demo())
                sensitive.requireActor(db.queryForObject(
                        "SELECT student_id FROM attempts WHERE id=? AND publication_id=?",
                        UUID.class,
                        attemptId,
                        publication));
            var attempt = rows.getFirst();
            if (resource.state().equals("DRAFT") || !attempt.state().equals("SUBMITTED") || attempt.result() == null)
                throw new PublicationFailure(409, "REVIEW_NOT_AVAILABLE");
            Instant time = now();
            Instant expiry = sensitive.expiresAt(time);
            if (expiry == null || !expiry.isAfter(time)) throw new PublicationFailure(503, "SENSITIVE_POLICY_PENDING");
            var stored = db.queryForMap(
                    "SELECT p.configuration::text,s.payload::text,s.schema_version,s.grading_policy_version,a.revision,sa.canonical_answers::text AS answers FROM publications p JOIN publication_snapshots s ON s.publication_id=p.id JOIN attempts a ON a.publication_id=p.id AND a.snapshot_id=s.publication_id JOIN submitted_answers sa ON sa.id=a.submitted_answers_id AND sa.attempt_id=a.id WHERE p.id=? AND a.id=?",
                    publication,
                    attemptId);
            var config = json.readValue(stored.get("configuration").toString(), PublicationConfiguration.class);
            var docs = new GradingDocuments();
            var payload = stored.get("payload").toString();
            var snapshot = docs.snapshot(
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    config,
                    ((Number) stored.get("schema_version")).intValue(),
                    ((Number) stored.get("grading_policy_version")).intValue());
            var answers = docs.replacement(stored.get("answers").toString(), snapshot);
            var root = docs.tree(payload).path("structuredContent").path("questions");
            var grades = db.queryForList(
                    "SELECT question_number,earned_weight,possible_weight FROM grading_question_results WHERE result_id=? ORDER BY question_number",
                    attempt.result().resultId());
            if (grades.size() != snapshot.questions().size()) throw new PublicationFailure(503, "GRADING_UNAVAILABLE");
            var questions = new ArrayList<AttemptViews.QuestionReview>();
            for (int i = 0; i < grades.size(); i++) {
                if (((Number) grades.get(i).get("question_number")).intValue() != i + 1)
                    throw new PublicationFailure(503, "GRADING_UNAVAILABLE");
                var q = root.get(i);
                var options = new ArrayList<AttemptViews.Option>();
                for (var option : q.path("options"))
                    options.add(new AttemptViews.Option(
                            option.path("label").asString(),
                            option.path("markdown").asString()));
                var question = new AttemptViews.Question(
                        i + 1, q.path("type").asString(), q.path("stemMarkdown").asString(), options);
                questions.add(new AttemptViews.QuestionReview(
                        question,
                        answer(answers.get(i)),
                        answer(snapshot.questions().get(i).key()),
                        decimal((BigDecimal) grades.get(i).get("earned_weight")),
                        decimal((BigDecimal) grades.get(i).get("possible_weight")),
                        q.path("explanationMarkdown").isNull()
                                ? null
                                : q.path("explanationMarkdown").asString()));
            }
            receipt(actor, publication, attemptId, "TEACHER_REVIEW", time, expiry);
            fresh(actor);
            return new Review(
                    publication,
                    attemptId,
                    publication,
                    attempt.state(),
                    stored.get("revision").toString(),
                    1,
                    attempt.startedAt(),
                    attempt.finalizedAt(),
                    attempt.result(),
                    List.copyOf(questions));
        });
    }

    public void receipt(
            ClosureStore.Teacher actor, UUID publication, UUID attempt, String purpose, Instant time, Instant expiry) {
        db.update(
                "INSERT INTO assessment_sensitive_read_receipts(id,publication_id,attempt_id,actor_id,purpose,accessed_at,expires_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                publication,
                attempt,
                actor.id(),
                purpose,
                Timestamp.from(time),
                Timestamp.from(expiry));
    }

    private AttemptViews.Answer answer(GradingPolicy.Answer answer) {
        var docs = new GradingDocuments();
        return switch (answer.type()) {
            case SINGLE_CHOICE -> json.readValue(docs.answerJson(answer), AttemptViews.Single.class);
            case MULTIPLE_CHOICE -> json.readValue(docs.answerJson(answer), AttemptViews.Multiple.class);
            case TRUE_FALSE_MATRIX -> json.readValue(docs.answerJson(answer), AttemptViews.Matrix.class);
            case NUMERIC_FILL -> json.readValue(docs.answerJson(answer), AttemptViews.Numeric.class);
        };
    }
}

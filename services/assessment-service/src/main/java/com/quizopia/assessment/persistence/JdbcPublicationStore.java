package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcPublicationStore implements PublicationStore {
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final SecureRandom random = new SecureRandom();

    public JdbcPublicationStore(JdbcTemplate db, PlatformTransactionManager manager, Clock clock) {
        this.db = db;
        this.clock = clock;
        db.setQueryTimeout(3);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    private <T> T safe(Supplier<T> work) {
        try {
            return work.get();
        } catch (PublicationFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private <T> T tx(Supplier<T> work) {
        return safe(() -> transaction.execute(status -> {
            db.execute("SET LOCAL lock_timeout='3s'");
            db.execute("SET LOCAL statement_timeout='3s'");
            return work.get();
        }));
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String encode(PublicationConfiguration c) {
        return json.writeValueAsString(c);
    }

    private Publication row(ResultSet rs, int index) throws SQLException {
        return new Publication(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                json.readValue(rs.getString("configuration"), PublicationConfiguration.class),
                Publication.State.valueOf(rs.getString("state")),
                rs.getLong("revision"),
                rs.getObject("opened_from_revision", Long.class),
                rs.getString("private_locator"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("opened_at") == null
                        ? null
                        : rs.getTimestamp("opened_at").toInstant());
    }

    private Publication find(String sql, Object... args) {
        var found = db.query(sql, this::row, args);
        if (found.size() != 1) throw PublicationFailure.missing();
        return found.getFirst();
    }

    public Publication owned(UUID actor, UUID id) {
        return safe(() -> find("SELECT * FROM publications WHERE id=? AND owner_id=?", id, actor));
    }

    private Publication lock(UUID actor, UUID id) {
        return find("SELECT * FROM publications WHERE id=? AND owner_id=? FOR UPDATE", id, actor);
    }

    public Publication replay(UUID actor, String key, PublicationConfiguration c) {
        return safe(() -> {
            var rows = db.queryForList(
                    "SELECT request_digest,publication_id FROM publication_creations WHERE owner_id=? AND key_digest=?",
                    actor,
                    digest(key));
            if (rows.isEmpty()) return null;
            String recorded = (String) rows.getFirst().get("request_digest");
            // Pre-V3 creation receipts hashed the record without maxAttempts. Accept that
            // spelling only for the historically equivalent finite default of one.
            var legacy = (tools.jackson.databind.node.ObjectNode) json.readTree(encode(c));
            legacy.remove("maxAttempts");
            if (!digest(encode(c)).equals(recorded)
                    && !(c.maxAttempts() == 1
                            && digest(json.writeValueAsString(legacy)).equals(recorded)))
                throw new PublicationFailure(409, "IDEMPOTENCY_CONFLICT");
            return owned(actor, (UUID) rows.getFirst().get("publication_id"));
        });
    }

    public Publication create(UUID actor, String key, PublicationConfiguration c) {
        return tx(() -> {
            // The durable unique replay key arbitrates concurrent insertions. The losing candidate is removed in this
            // transaction.
            UUID id = UUID.randomUUID();
            String locator = locator(c, null);
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            db.update(
                    "INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,classroom_id,start_at,end_at,duration_minutes,monitoring_choice,max_attempts,state,revision,private_locator,locator_digest,created_at) VALUES (?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?,?)",
                    id,
                    actor,
                    encode(c),
                    c.quizId(),
                    c.quizVersionId(),
                    c.versionNumber(),
                    c.audience().name(),
                    c.classroomId(),
                    Timestamp.from(c.startAt()),
                    Timestamp.from(c.endAt()),
                    c.durationMinutes(),
                    c.monitoringChoice().name(),
                    c.maxAttempts(),
                    locator,
                    locator == null ? null : digest(locator),
                    Timestamp.from(now));
            int inserted = db.update(
                    "INSERT INTO publication_creations(owner_id,key_digest,request_digest,publication_id) VALUES(?,?,?,?) ON CONFLICT(owner_id,key_digest) DO NOTHING",
                    actor,
                    digest(key),
                    digest(encode(c)),
                    id);
            if (inserted == 0) {
                db.update("DELETE FROM publications WHERE id=?", id);
                return replay(actor, key, c);
            }
            return owned(actor, id);
        });
    }

    private String locator(PublicationConfiguration c, Publication previous) {
        if (c.audience() != PublicationConfiguration.Audience.PUBLIC) return null;
        if (previous != null && previous.privateLocator() != null) return previous.privateLocator();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void draft(Publication p, long revision) {
        if (revision < 0 || p.state() != Publication.State.DRAFT || p.revision() != revision)
            throw PublicationFailure.conflict();
    }

    private void replace(Publication p, PublicationConfiguration c) {
        String locator = locator(c, p);
        db.update(
                "UPDATE publications SET configuration=?::jsonb,quiz_id=?,quiz_version_id=?,version_number=?,audience=?,classroom_id=?,start_at=?,end_at=?,duration_minutes=?,monitoring_choice=?,max_attempts=?,private_locator=?,locator_digest=?,revision=revision+1 WHERE id=?",
                encode(c),
                c.quizId(),
                c.quizVersionId(),
                c.versionNumber(),
                c.audience().name(),
                c.classroomId(),
                Timestamp.from(c.startAt()),
                Timestamp.from(c.endAt()),
                c.durationMinutes(),
                c.monitoringChoice().name(),
                c.maxAttempts(),
                locator,
                locator == null ? null : digest(locator),
                p.id());
    }

    public Publication update(UUID actor, UUID id, long revision, PublicationConfiguration c) {
        return tx(() -> {
            var p = lock(actor, id);
            draft(p, revision);
            replace(p, c);
            return owned(actor, id);
        });
    }

    public Publication open(UUID actor, UUID id, long revision, String snapshot) {
        return tx(() -> {
            var p = lock(actor, id);
            if (p.state() == Publication.State.OPEN && Long.valueOf(revision).equals(p.openedFromRevision())) return p;
            draft(p, revision);
            if (p.configuration().monitoringChoice() == PublicationConfiguration.MonitoringChoice.UNCONFIGURED)
                throw new PublicationFailure(400, "INVALID_REQUEST");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            db.update(
                    "INSERT INTO publication_snapshots(publication_id,schema_version,grading_policy_version,payload,created_at) VALUES(?,1,1,?::jsonb,?)",
                    id,
                    snapshot,
                    Timestamp.from(now));
            db.update(
                    "UPDATE publications SET state='OPEN',snapshot_id=id,opened_at=?,opened_from_revision=revision,revision=revision+1 WHERE id=?",
                    Timestamp.from(now),
                    id);
            return owned(actor, id);
        });
    }

    public Publication extend(UUID actor, UUID id, long revision, Instant endAt, int duration) {
        return tx(() -> {
            var p = lock(actor, id);
            if (Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM closure_requests WHERE publication_id=? AND state IN ('PENDING','FINALIZING'))",
                    Boolean.class,
                    id))) throw new PublicationFailure(409, "CLOSURE_PENDING");
            var c = p.configuration();
            if (p.state() != Publication.State.OPEN || p.revision() != revision) throw PublicationFailure.conflict();
            if (endAt == null
                    || endAt.getNano() % 1000 != 0
                    || endAt.isBefore(c.endAt())
                    || (c.durationMinutes() == 0 ? duration != 0 : duration < c.durationMinutes())
                    || duration > 240) throw new PublicationFailure(400, "INVALID_REQUEST");
            // Update only schedule keys. In particular, do not insert maxAttempts into
            // immutable legacy OPEN JSON that omitted it; relational max_attempts stays 1.
            db.update(
                    "UPDATE publications SET configuration=jsonb_set(jsonb_set(configuration,'{endAt}',to_jsonb(?::text)),'{durationMinutes}',to_jsonb(?::integer)),end_at=?,duration_minutes=?,revision=revision+1 WHERE id=?",
                    endAt.toString(),
                    duration,
                    Timestamp.from(endAt),
                    duration,
                    id);
            return owned(actor, id);
        });
    }

    public Publication publicEntry(String locator) {
        return safe(() -> find(
                "SELECT * FROM publications WHERE locator_digest=? AND state='OPEN' AND audience='PUBLIC'",
                digest(locator)));
    }

    public Publication classEntry(UUID id) {
        return safe(() -> find("SELECT * FROM publications WHERE id=? AND state='OPEN' AND audience='CLASS'", id));
    }
}

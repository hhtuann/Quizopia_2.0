package com.quizopia.assessment.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local recoverable outbox projection. Caller owns Publication-first transaction/locks. */
public final class ServerAuditPublisher {
    private final JdbcTemplate db;
    private final AuditEncryption crypto;

    public ServerAuditPublisher(JdbcTemplate db, AuditEncryption crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    public void publish(UUID attempt) {
        var rows = db.queryForList(
                "SELECT p.* FROM assessment_audit_pending p WHERE p.attempt_id=? AND p.expires_at>assessment_time() AND NOT EXISTS(SELECT FROM assessment_answer_audit a WHERE a.attempt_id=p.attempt_id AND a.event_id=p.event_id) ORDER BY p.ordinal LIMIT 200 FOR UPDATE OF p",
                attempt);
        Instant now = db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
        for (var row : rows) {
            UUID event = (UUID) row.get("event_id");
            Instant expiry = ((Timestamp) row.get("expires_at")).toInstant();
            if (!expiry.isAfter(now)) {
                continue;
            }
            var fact = new ServerAuditFact(
                    event,
                    row.get("event_type").toString(),
                    ((Timestamp) row.get("occurred_at")).toInstant(),
                    (Integer) row.get("question_number"),
                    row.get("assessment_revision") == null
                            ? null
                            : row.get("assessment_revision").toString(),
                    (String) row.get("operation_kind"),
                    (String) row.get("terminal_reason"),
                    (UUID) row.get("result_id"),
                    (UUID) row.get("notice_id"));
            UUID pub = (UUID) row.get("publication_id"), actor = (UUID) row.get("actor_id");
            var encrypted = crypto.encrypt(fact.context(pub, attempt, actor), fact.canonical());
            db.update(
                    "INSERT INTO assessment_answer_audit(attempt_id,publication_id,event_id,ordinal,actor_id,source,event_type,question_number,assessment_revision,occurred_at,received_at,expires_at,key_id,protected_input,operation_kind,terminal_reason,result_id,notice_id) VALUES(?,?,?,?,?,'SERVER_AUTHORITY',?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(attempt_id,event_id) DO NOTHING",
                    attempt,
                    pub,
                    event,
                    row.get("ordinal"),
                    actor,
                    fact.type(),
                    fact.questionNumber(),
                    row.get("assessment_revision"),
                    row.get("occurred_at"),
                    row.get("occurred_at"),
                    row.get("expires_at"),
                    encrypted.keyId(),
                    encrypted.bytes(),
                    fact.operationKind(),
                    fact.terminalReason(),
                    fact.resultId(),
                    fact.noticeId());
            var stored = db.queryForMap(
                    "SELECT key_id,protected_input,ordinal,actor_id FROM assessment_answer_audit WHERE attempt_id=? AND event_id=?",
                    attempt,
                    event);
            var verified = ServerAuditFact.parse(
                    crypto.decrypt(stored.get("key_id").toString(), fact.context(pub, attempt, actor), (byte[])
                            stored.get("protected_input")));
            if (!verified.equals(fact)
                    || !stored.get("ordinal").equals(row.get("ordinal"))
                    || !actor.equals(stored.get("actor_id"))) throw AuditEncryption.unavailable();
            db.queryForList("SELECT assessment_ack_audit(?)", event);
        }
    }
}

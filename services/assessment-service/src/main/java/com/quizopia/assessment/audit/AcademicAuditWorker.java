package com.quizopia.assessment.audit;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded local recovery; no broker or remote calls inside academic transactions. */
@Component
@Profile("academic-demo")
public class AcademicAuditWorker {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ServerAuditPublisher publisher;

    public AcademicAuditWorker(JdbcTemplate db, PlatformTransactionManager manager, AuditEncryption crypto) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
        publisher = new ServerAuditPublisher(db, crypto);
    }

    @Scheduled(fixedDelayString = "${quizopia.assessment.demo.publish-millis:1000}")
    public void recover() {
        // Failures remain pending, never print exception/body/key material.
        for (UUID attempt : db.query(
                "SELECT DISTINCT a.attempt_id FROM assessment_audit_pending a JOIN publications p ON p.id=a.publication_id WHERE a.expires_at>assessment_time() AND NOT EXISTS(SELECT FROM quiz_revocation_fences f WHERE f.quiz_id=p.quiz_id) AND NOT EXISTS(SELECT FROM assessment_answer_audit x WHERE x.attempt_id=a.attempt_id AND x.event_id=a.event_id) ORDER BY a.attempt_id LIMIT 50",
                (r, n) -> r.getObject(1, UUID.class))) {
            try {
                tx.executeWithoutResult(s -> {
                    db.execute("SET LOCAL lock_timeout='1000ms'");
                    db.execute("SET LOCAL statement_timeout='3000ms'");
                    var pub = db.query(
                            "SELECT p.id,p.quiz_id FROM publications p JOIN attempts a ON a.publication_id=p.id WHERE a.id=? FOR UPDATE OF p SKIP LOCKED",
                            (r, n) -> r.getObject(1, UUID.class),
                            attempt);
                    if (!pub.isEmpty()) {
                        new com.quizopia.assessment.application.QuizRevocationGate(db)
                                .requirePublicationOpen(pub.getFirst());
                        publisher.publish(attempt);
                    }
                });
            } catch (RuntimeException unavailable) {
                /* recover next bounded tick */
            }
        }
    }
}

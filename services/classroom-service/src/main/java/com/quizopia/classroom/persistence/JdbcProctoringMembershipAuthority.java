package com.quizopia.classroom.persistence;

import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.application.ProctoringMembershipAuthority;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One primary-database statement samples Teacher ownership, actual membership and server time. */
@Repository
@ConditionalOnProperty(name = "quizopia.internal-authority.proctoring-membership-enabled", havingValue = "true")
public class JdbcProctoringMembershipAuthority implements ProctoringMembershipAuthority {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    public JdbcProctoringMembershipAuthority(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(false);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(3);
    }

    @Override
    public Confirmation confirm(UUID teacherId, UUID classroomId, UUID studentId) {
        return transaction.execute(status -> {
            db.execute("SET LOCAL lock_timeout='1s'");
            db.execute("SET LOCAL statement_timeout='2s'");
            var matches = db.query(
                    """
                    WITH sample AS MATERIALIZED (SELECT clock_timestamp() AS checked_at,pg_is_in_recovery() AS standby)
                    SELECT s.standby,c.id,m.user_id,s.checked_at FROM sample s
                    LEFT JOIN classrooms c ON c.id=? AND c.owner_teacher_user_id=?
                    LEFT JOIN classroom_memberships m ON m.classroom_id=c.id AND m.user_id=?
                    """,
                    (row, index) -> new Sample(
                            row.getBoolean(1),
                            row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class),
                            row.getTimestamp(4).toInstant()),
                    classroomId,
                    teacherId,
                    studentId);
            if (matches.size() != 1 || matches.getFirst().standby())
                throw new DataAccessResourceFailureException("Primary Classroom authority unavailable");
            var sample = matches.getFirst();
            if (sample.classroomId() == null || sample.studentId() == null)
                throw new ClassroomNotFoundException(classroomId);
            return new Confirmation(sample.classroomId(), sample.studentId(), sample.checkedAt());
        });
    }

    private record Sample(boolean standby, UUID classroomId, UUID studentId, Instant checkedAt) {}
}

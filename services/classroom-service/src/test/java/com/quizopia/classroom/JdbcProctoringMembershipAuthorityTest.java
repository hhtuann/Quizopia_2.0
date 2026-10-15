package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.persistence.JdbcProctoringMembershipAuthority;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Recovery state is an explicit result-set double; this does not prove a real failover topology. */
class JdbcProctoringMembershipAuthorityTest {
    JdbcTemplate db;
    PlatformTransactionManager manager;
    ResultSet row;
    JdbcProctoringMembershipAuthority authority;
    UUID teacher = UUID.randomUUID(), classroom = UUID.randomUUID(), student = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fixture() throws Exception {
        db = mock(JdbcTemplate.class);
        manager = mock(PlatformTransactionManager.class);
        row = mock(ResultSet.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(row.getObject(2, UUID.class)).thenReturn(classroom);
        when(row.getObject(3, UUID.class)).thenReturn(student);
        when(row.getTimestamp(4)).thenReturn(Timestamp.from(Instant.now()));
        doAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                })
                .when(db)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        authority = new JdbcProctoringMembershipAuthority(db, manager);
    }

    @Test
    void primaryUsesReadCommittedReadWriteAuthorityAndExactBindings() {
        var result = authority.confirm(teacher, classroom, student);
        assertEquals(classroom, result.classroomId());
        assertEquals(student, result.studentId());
        verify(manager)
                .getTransaction(argThat(definition -> !definition.isReadOnly()
                        && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED
                        && definition.getTimeout() == 3));
        verify(db)
                .query(contains("pg_is_in_recovery()"), any(RowMapper.class), eq(classroom), eq(teacher), eq(student));
    }

    @Test
    void standbyCannotConfirmEvenWhenOwnershipAndMembershipRowsMatch() throws Exception {
        when(row.getBoolean(1)).thenReturn(true);
        assertThrows(DataAccessResourceFailureException.class, () -> authority.confirm(teacher, classroom, student));
        verify(manager).rollback(any());
    }

    @Test
    void primaryMissingMembershipIsMaskedMissingRatherThanPositiveConfirmation() throws Exception {
        when(row.getObject(3, UUID.class)).thenReturn(null);
        assertThrows(ClassroomNotFoundException.class, () -> authority.confirm(teacher, classroom, student));
    }
}

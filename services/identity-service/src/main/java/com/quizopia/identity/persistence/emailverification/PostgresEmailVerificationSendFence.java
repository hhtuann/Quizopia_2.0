package com.quizopia.identity.persistence.emailverification;

import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Profile("!test")
public final class PostgresEmailVerificationSendFence implements EmailVerificationSendFence {
    private static final long LOCK_NAMESPACE = 0x51555A4F504941L;
    private static final String SESSION_LOCK_SQL =
            "SELECT pg_advisory_lock(hashtextextended(?, " + LOCK_NAMESPACE + "))";
    private static final String SESSION_UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended(?, " + LOCK_NAMESPACE + "))";
    private static final String TRANSACTION_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?, " + LOCK_NAMESPACE + "))";
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public PostgresEmailVerificationSendFence(
            EmailOutboxDispatcherDatabase dispatcherDatabase, JdbcTemplate jdbcTemplate) {
        this(dispatcherDatabase.dataSource(), jdbcTemplate);
    }

    PostgresEmailVerificationSendFence(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public SendPermit acquireForDispatch(String exactEmail) {
        requireEmail(exactEmail);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            executeLock(connection, SESSION_LOCK_SQL, exactEmail);
            return new PostgresSendPermit(connection, exactEmail);
        } catch (Exception exception) {
            var failure = new IllegalStateException("Verification email send fence is unavailable", exception);
            invalidateAndClose(connection, failure);
            throw failure;
        }
    }

    @Override
    public void serializeMutation(String exactEmail) {
        requireEmail(exactEmail);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Verification email mutation fence requires an active transaction");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            executeLock(connection, TRANSACTION_LOCK_SQL, exactEmail);
            return null;
        });
    }

    private static void executeLock(Connection connection, String sql, String exactEmail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, exactEmail);
            statement.execute();
        }
    }

    private static void requireEmail(String exactEmail) {
        Objects.requireNonNull(exactEmail, "exactEmail");
        if (exactEmail.isBlank()) {
            throw new IllegalArgumentException("exactEmail must not be blank");
        }
    }

    private static void invalidateAndClose(Connection connection, RuntimeException primaryFailure) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(DIRECT_EXECUTOR);
        } catch (Exception cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
        try {
            connection.close();
        } catch (Exception cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private static final class PostgresSendPermit implements SendPermit {
        private final Connection connection;
        private final String exactEmail;
        private boolean closed;

        private PostgresSendPermit(Connection connection, String exactEmail) {
            this.connection = connection;
            this.exactEmail = exactEmail;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try (PreparedStatement statement = connection.prepareStatement(SESSION_UNLOCK_SQL)) {
                statement.setString(1, exactEmail);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        failure = new IllegalStateException("Verification email send fence ownership was lost");
                    }
                }
            } catch (SQLException exception) {
                if (failure == null) {
                    failure =
                            new IllegalStateException("Verification email send fence could not be released", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                invalidateAndClose(connection, failure);
                throw failure;
            }
            try {
                connection.close();
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Verification email send-fence connection could not be closed", exception);
            }
        }
    }
}

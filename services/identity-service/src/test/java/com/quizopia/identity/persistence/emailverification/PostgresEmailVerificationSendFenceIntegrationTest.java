package com.quizopia.identity.persistence.emailverification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quizopia.identity.application.emailverification.delivery.ClaimedEmailVerificationOutboxJob;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxStore;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDelivery;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDeliveryException;
import com.quizopia.identity.configuration.EmailOutboxDispatcherProperties;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.outbox.EncryptedOutboxPayload;
import com.quizopia.identity.security.outbox.OutboxPayloadBinding;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresEmailVerificationSendFenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    private static final String EMAIL = "fence-pool@gmail.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void dedicatedDispatcherDatabaseUsesTheBoundedPoolAndClosesCleanly() throws Exception {
        var properties = new DataSourceProperties();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        var database = new EmailOutboxDispatcherDatabase(properties);
        HikariDataSource dataSource = (HikariDataSource) database.dataSource();
        try {
            assertEquals(EmailOutboxDispatcherDatabase.MAXIMUM_POOL_SIZE, dataSource.getMaximumPoolSize());
            assertFalse(dataSource.isClosed());
            assertPoolRecovered(dataSource);
        } finally {
            database.close();
        }
        assertTrue(dataSource.isClosed());
    }

    @Test
    void acquisitionFailureAbortsAndClosesTheHikariProxy() {
        try (HikariDataSource pool = pool(1)) {
            var fence = new PostgresEmailVerificationSendFence(
                    failingDataSource(pool, FailurePoint.ACQUIRE), new JdbcTemplate(pool));

            assertThrows(IllegalStateException.class, () -> fence.acquireForDispatch(EMAIL));

            assertPoolRecovered(pool);
        }
    }

    @Test
    void unlockFailureAbortsAndClosesTheHikariProxy() {
        try (HikariDataSource pool = pool(1)) {
            var fence = new PostgresEmailVerificationSendFence(
                    failingDataSource(pool, FailurePoint.UNLOCK), new JdbcTemplate(pool));
            var permit = fence.acquireForDispatch(EMAIL);

            assertThrows(IllegalStateException.class, permit::close);

            assertPoolRecovered(pool);
        }
    }

    @Test
    void cleanupFailuresAreSuppressedWithoutReplacingTheAcquisitionFailure() {
        AtomicInteger abortCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> true;
                        case "prepareStatement" -> throw new SQLException("primary acquisition failure");
                        case "abort" -> {
                            abortCalls.incrementAndGet();
                            throw new SQLException("abort cleanup failure");
                        }
                        case "close" -> {
                            closeCalls.incrementAndGet();
                            throw new SQLException("close cleanup failure");
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        DataSource dataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return connection;
            }
        };
        var fence = new PostgresEmailVerificationSendFence(dataSource, mock(JdbcTemplate.class));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> fence.acquireForDispatch(EMAIL));

        assertEquals("primary acquisition failure", failure.getCause().getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(1, abortCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void smtpFailureReleasesTheFenceConnection() {
        try (HikariDataSource pool = pool(1)) {
            VerificationEmailDelivery delivery = message -> {
                throw new VerificationEmailDeliveryException(
                        VerificationEmailDeliveryException.Category.SMTP_TRANSIENT_FAILURE);
            };

            dispatch(pool, delivery, false);

            assertPoolRecovered(pool);
        }
    }

    @Test
    void interruptedDeliveryReleasesTheFenceConnection() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            VerificationEmailDelivery delivery = message -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("simulated interrupted delivery");
            };
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> dispatch(pool, delivery, false)).get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertPoolRecovered(pool);
        }
    }

    @Test
    void finalizationFailureAfterSmtpReleasesTheFenceConnection() {
        try (HikariDataSource pool = pool(1)) {
            AtomicBoolean delivered = new AtomicBoolean();

            dispatch(pool, message -> delivered.set(true), true);

            assertTrue(delivered.get());
            assertPoolRecovered(pool);
        }
    }

    private static void dispatch(HikariDataSource pool, VerificationEmailDelivery delivery, boolean failFinalization) {
        EmailVerificationOutboxStore store = mock(EmailVerificationOutboxStore.class);
        OutboxPayloadCipher cipher = mock(OutboxPayloadCipher.class);
        ClaimedEmailVerificationOutboxJob job = job();
        when(store.claimDue(anyString(), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(job));
        when(store.renewClaim(any(UUID.class), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(store.reserveAttempt(any(UUID.class), anyString(), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(OptionalInt.of(1));
        when(store.isDeliverable(any(UUID.class), anyString())).thenReturn(true);
        when(store.scheduleRetry(any(UUID.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(true);
        if (failFinalization) {
            when(store.markSent(any(UUID.class), anyString(), any(Instant.class)))
                    .thenThrow(new IllegalStateException("simulated finalization failure"));
        } else {
            when(store.markSent(any(UUID.class), anyString(), any(Instant.class)))
                    .thenReturn(true);
        }
        when(cipher.decrypt(any(EncryptedOutboxPayload.class), any(OutboxPayloadBinding.class)))
                .thenReturn(RawEmailVerificationOtp.from("123456"));
        var properties = new EmailOutboxDispatcherProperties();
        properties.setEnabled(true);
        properties.setFromAddress("no-reply@quizopia.test");
        properties.setLeaseDuration(Duration.ofMinutes(1));
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        try {
            var fence = new PostgresEmailVerificationSendFence(pool, new JdbcTemplate(pool));
            var dispatcher = new EmailVerificationOutboxDispatcher(
                    store,
                    cipher,
                    delivery,
                    fence,
                    properties,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    "pool-recovery-worker",
                    heartbeat);
            assertEquals(1, dispatcher.dispatchDueBatch());
        } finally {
            heartbeat.shutdownNow();
        }
    }

    private static ClaimedEmailVerificationOutboxJob job() {
        return new ClaimedEmailVerificationOutboxJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "pool-recovery-user",
                EMAIL,
                "EMAIL_VERIFICATION_OTP",
                NOW.plusSeconds(600),
                0,
                new EncryptedOutboxPayload(new byte[] {1}, new byte[] {2}, "test-v1", 1));
    }

    private static HikariDataSource pool(int maximumSize) {
        var configuration = new HikariConfig();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername(POSTGRES.getUsername());
        configuration.setPassword(POSTGRES.getPassword());
        configuration.setMaximumPoolSize(maximumSize);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(500);
        configuration.setPoolName("send-fence-recovery-test-" + UUID.randomUUID());
        return new HikariDataSource(configuration);
    }

    private static DataSource failingDataSource(HikariDataSource delegate, FailurePoint failurePoint) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return failureInjectingConnection(delegate.getConnection(), failurePoint);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return failureInjectingConnection(delegate.getConnection(username, password), failurePoint);
            }
        };
    }

    private static Connection failureInjectingConnection(Connection delegate, FailurePoint failurePoint) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if ("prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && failurePoint.matches(sql)) {
                        throw new SQLException("simulated advisory-lock SQL failure");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static void assertPoolRecovered(HikariDataSource pool) {
        try (Connection connection = pool.getConnection();
                var statement = connection.prepareStatement("SELECT 1");
                var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        } catch (SQLException exception) {
            throw new AssertionError("Hikari pool connection was not recovered", exception);
        }
    }

    private enum FailurePoint {
        ACQUIRE {
            @Override
            boolean matches(String sql) {
                return sql.contains("pg_advisory_lock(");
            }
        },
        UNLOCK {
            @Override
            boolean matches(String sql) {
                return sql.contains("pg_advisory_unlock(");
            }
        };

        abstract boolean matches(String sql);
    }
}

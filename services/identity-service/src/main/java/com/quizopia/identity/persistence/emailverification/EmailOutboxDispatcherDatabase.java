package com.quizopia.identity.persistence.emailverification;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC capacity reserved for email-outbox dispatch coordination and state changes.
 *
 * <p>The production dispatcher is locally single-flight. Its maximum concurrent database need is
 * one session advisory-lock connection, one lease-heartbeat connection, and one dispatcher state
 * operation connection. Keeping those three connections outside the primary transactional pool
 * prevents mutations waiting on the advisory fence from starving the live dispatcher.
 */
@Component
@Profile("!test")
public final class EmailOutboxDispatcherDatabase {
    static final int MAXIMUM_POOL_SIZE = 3;
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public EmailOutboxDispatcherDatabase(DataSourceProperties primaryProperties) {
        Objects.requireNonNull(primaryProperties, "primaryProperties");
        HikariDataSource dispatcherDataSource = primaryProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dispatcherDataSource.setPoolName("quizopia-identity-email-outbox-dispatcher");
        dispatcherDataSource.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        dispatcherDataSource.setMinimumIdle(0);
        dispatcherDataSource.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        this.dataSource = dispatcherDataSource;
        this.jdbcTemplate = new JdbcTemplate(dispatcherDataSource);
    }

    DataSource dataSource() {
        return dataSource;
    }

    JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}

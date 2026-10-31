package com.quizopia.assessment.audit;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Separate custodian connection, only scoped expiry EXECUTE; runtime has no DELETE grant. */
@Component
@Profile("academic-demo")
public class AcademicRetentionWorker {
    private final JdbcTemplate custody;

    public AcademicRetentionWorker(Environment env) {
        long interval = env.getProperty("quizopia.assessment.demo.cleanup-millis", Long.class, 3600000L);
        if (interval < 1000 || interval > 3600000)
            throw new IllegalStateException("Demo expiry must run at least hourly");
        String url = env.getRequiredProperty("spring.datasource.url");
        String user = env.getRequiredProperty("quizopia.assessment.demo.custodian-user");
        if (!user.equals("quizopia_demo_custodian"))
            throw new IllegalStateException("Separate demo custodian required");
        custody = new JdbcTemplate(new DriverManagerDataSource(
                url, user, env.getRequiredProperty("quizopia.assessment.demo.custodian-password")));
        custody.setQueryTimeout(10);
    }

    @Scheduled(fixedDelayString = "${quizopia.assessment.demo.cleanup-millis:3600000}")
    public void cleanup() {
        // An hourly bounded drain, idempotent across restart and competing custodians.
        for (int n = 0; n < 1000; n++) {
            Integer removed = custody.queryForObject("SELECT assessment_expire_audit(5000)", Integer.class);
            if (removed == null || removed < 5000) return;
        }
    }
}

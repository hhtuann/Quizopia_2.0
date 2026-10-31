package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deliberately loopback-only fictional-account tier. Not a production activation provider. */
@Configuration(proxyBeanMethods = false)
@Profile("academic-demo")
@org.springframework.scheduling.annotation.EnableScheduling
public class AcademicDemoConfiguration {
    public static void validate(Environment env) {
        if (!Set.of("127.0.0.1", "::1").contains(env.getProperty("server.address", ""))
                || !env.getProperty("quizopia.assessment.demo.synthetic-only", Boolean.class, false)
                || Arrays.stream(env.getActiveProfiles())
                        .anyMatch(p -> Set.of("prod", "production").contains(p))
                || !env.getProperty("spring.datasource.url", "")
                        .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/[^?]+(?:\\?.*)?"))
            throw new IllegalStateException("Academic demo requires isolated loopback synthetic configuration");
    }

    @Bean
    public SensitiveAssessmentPolicy academicDemoPolicy(
            Environment env, @org.springframework.context.annotation.Lazy JdbcTemplate db) {
        validate(env);
        return new SensitiveAssessmentPolicy() {
            public Instant expiresAt(Instant now) {
                return now.plus(180, ChronoUnit.DAYS);
            }

            public Instant observationExpiry(Instant now) {
                return now.plus(30, ChronoUnit.DAYS);
            }

            public boolean disclosureAvailable() {
                return true;
            }

            public boolean demo() {
                return true;
            }

            public void requireActor(UUID actor) {
                if (!Boolean.TRUE.equals(db.queryForObject(
                        "SELECT EXISTS(SELECT FROM assessment_demo_subjects WHERE actor_id=? AND fixture='FICTIONAL_ACADEMIC_DEMO_V1')",
                        Boolean.class,
                        actor))) throw new PublicationFailure(403, "DEMO_ACCOUNT_REQUIRED");
            }
        };
    }

    @Bean
    public AuditEncryption academicDemoEncryption(Environment env) {
        validate(env);
        var configured = Binder.get(env)
                .bind("quizopia.assessment.demo.keys", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        String current = env.getProperty("quizopia.assessment.demo.current-key", "");
        var keys = new HashMap<String, AuditEncryption.AesGcm>();
        try {
            configured.forEach((id, encoded) -> keys.put(
                    id, new AuditEncryption.AesGcm(id, Base64.getDecoder().decode(encoded))));
            if (!keys.containsKey(current) || keys.size() > 4) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid disposable demo key configuration");
        }
        return new AuditEncryption() {
            public Protected encrypt(String context, byte[] bytes) {
                return keys.get(current).encrypt(context, bytes);
            }

            public byte[] decrypt(String id, String context, byte[] bytes) {
                var key = keys.get(id);
                if (key == null) throw AuditEncryption.unavailable();
                return key.decrypt(id, context, bytes);
            }

            public String toString() {
                return "AcademicDemoEncryption[redacted]";
            }
        };
    }
}

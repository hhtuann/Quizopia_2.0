package com.quizopia.proctoring;

import static com.quizopia.proctoring.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.api.MonitoringController;
import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Actual default configuration: a valid USER token cannot activate monitoring. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ClosedBusinessBoundaryTest.Keys.class)
class MonitoringDefaultDisabledHttpTest {
    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    MonitoringProperties properties;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/proctoring/monitoring/sessions/by-attempt/" + ACTOR,
                "POST /api/proctoring/monitoring/sessions",
                "GET /api/proctoring/monitoring/sessions/" + ACTOR,
                "POST /api/proctoring/monitoring/sessions/" + ACTOR + "/events",
                "GET /api/proctoring/monitoring/sessions/" + ACTOR + "/events",
                "POST /api/proctoring/monitoring/sessions/" + ACTOR + "/reconcile"
            })
    void eachImplementedOperationFailsClosedByDefault(String operation) {
        String[] parts = operation.split(" ", 2);
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .method(HttpMethod.valueOf(parts[0]))
                .uri(parts[1])
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
    }

    @Test
    void disabledConfigurationCreatesNoStoreAuthorityOrController() {
        assertFalse(properties.isEnabled());
        assertTrue(context.getBeansOfType(MonitoringLookupService.class).isEmpty());
        assertTrue(context.getBeansOfType(MonitoringLookup.class).isEmpty());
        assertTrue(context.getBeansOfType(com.quizopia.proctoring.api.MonitoringLookupController.class)
                .isEmpty());
        assertTrue(context.getBeansOfType(MonitoringStore.class).isEmpty());
        assertTrue(context.getBeansOfType(MonitoringAuthority.class).isEmpty());
        assertTrue(context.getBeansOfType(MonitoringController.class).isEmpty());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void privateBrokerErrorDiagnosticsCannotReachApplicationLogs(CapturedOutput output) {
        var logger = org.slf4j.LoggerFactory.getLogger("com.rabbitmq.client.impl.ForgivingExceptionHandler");
        logger.error("synthetic-private-broker-reply", new IllegalStateException("synthetic-private-vhost"));
        assertFalse(logger.isErrorEnabled());
        assertFalse(output.getAll().contains("synthetic-private-broker-reply"));
        assertFalse(output.getAll().contains("synthetic-private-vhost"));
    }
}

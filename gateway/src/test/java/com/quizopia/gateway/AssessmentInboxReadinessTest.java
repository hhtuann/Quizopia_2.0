package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.gateway.security.admission.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.*;
import reactor.core.publisher.Flux;

class AssessmentInboxReadinessTest {
    private String status(boolean inbox, boolean admission, boolean route) {
        var properties = new AdmissionProperties();
        properties.setEnabled(admission);
        var definition = new RouteDefinition();
        definition.setId("assessment-service");
        RouteDefinitionLocator locator = () -> route ? Flux.just(definition) : Flux.empty();
        return new AssessmentInboxReadiness(new InboxProperties(inbox), properties, locator)
                .health()
                .block(Duration.ofSeconds(1))
                .getStatus()
                .getCode();
    }

    @Test
    void enabledExactRouteAndAdmissionReady() {
        assertEquals("UP", status(true, true, true));
    }

    @Test
    void disabledInboxIsNotReady() {
        assertEquals("UNKNOWN", status(false, true, true));
    }

    @Test
    void missingAdmissionOrRouteIsNotReady() {
        assertEquals("UNKNOWN", status(true, false, true));
        assertEquals("UNKNOWN", status(true, true, false));
    }
}

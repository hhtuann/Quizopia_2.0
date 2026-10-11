package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.identity.configuration.UserAdmissionProperties;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserAdmissionConfigurationTest {
    @Test
    void defaultsCannotActivateAdmissionOrReconciliation() {
        var properties = new UserAdmissionProperties();
        assertFalse(properties.isEnabled());
        assertFalse(properties.isReconciliationEnabled());
        assertTrue(properties.isRequireSecureTransport());
        assertTrue(properties.getAllowedClients().isEmpty());
        properties.validate();
    }

    @Test
    void activationRequiresExactNonemptyCallerAllowlist() {
        var properties = new UserAdmissionProperties();
        properties.setEnabled(true);
        assertThrows(IllegalStateException.class, properties::validate);
        for (String client : new String[] {"*", "assessment-*", "assessment-service ", "a,b"}) {
            properties.setAllowedClients(Set.of(client));
            assertThrows(IllegalStateException.class, properties::validate);
        }
        properties.setAllowedClients(Set.of("assessment-service"));
        properties.validate();
    }

    @Test
    void reconciliationAndQueryControlsAreBounded() {
        var properties = new UserAdmissionProperties();
        properties.setBatchSize(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setBatchSize(501);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setBatchSize(5);
        properties.setQueryTimeoutSeconds(0);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setQueryTimeoutSeconds(31);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setQueryTimeoutSeconds(2);
        properties.setPollingInterval(Duration.ZERO);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setPollingInterval(Duration.ofSeconds(1));
        properties.validate();
    }
}

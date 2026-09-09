package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthorizationServerPropertiesTest {
    @Test
    void acceptsExternallyConfiguredIssuerAndPositiveServiceTokenTtl() {
        AuthorizationServerProperties properties = enabledProperties();

        properties.validateRequiredValues();

        assertEquals("https://identity.test", properties.requiredIssuer());
        assertEquals(Duration.ofSeconds(45), properties.requiredServiceAccessTokenTtl());
    }

    @Test
    void rejectsMissingIssuerWhenEnabled() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setIssuer(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validateRequiredValues);

        assertEquals(
                "Authorization Server issuer is required when the service-token surface is enabled",
                exception.getMessage());
    }

    @Test
    void rejectsRelativeIssuerWhenEnabled() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setIssuer("identity-service");

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validateRequiredValues);

        assertEquals("Authorization Server issuer must be an absolute URI", exception.getMessage());
    }

    @Test
    void rejectsNonPositiveServiceTokenTtlWhenEnabled() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setServiceAccessTokenTtl(Duration.ZERO);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validateRequiredValues);

        assertEquals(
                "A positive service access-token TTL is required when the service-token surface is enabled",
                exception.getMessage());
    }

    @Test
    void disabledSurfaceDoesNotRequireSecurityValues() {
        AuthorizationServerProperties properties = new AuthorizationServerProperties();

        assertDoesNotThrow(properties::validateRequiredValues);
    }

    private static AuthorizationServerProperties enabledProperties() {
        AuthorizationServerProperties properties = new AuthorizationServerProperties();
        properties.setEnabled(true);
        properties.setIssuer("https://identity.test");
        properties.setServiceAccessTokenTtl(Duration.ofSeconds(45));
        return properties;
    }
}

package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthorizationServerPropertiesTest {
    @Test
    void acceptsExternallyConfiguredIssuerAndPositiveTokenTtls() {
        AuthorizationServerProperties properties = enabledProperties();

        properties.validateRequiredValues();

        assertEquals("https://identity.test", properties.requiredIssuer());
        assertEquals(Duration.ofSeconds(45), properties.requiredServiceAccessTokenTtl());
        assertEquals(Duration.ofMinutes(5), properties.requiredUserAccessTokenTtl());
    }

    @Test
    void rejectsMissingIssuerWhenEnabled() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setIssuer(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validateRequiredValues);

        assertEquals("Authorization Server issuer is required when token issuance is enabled", exception.getMessage());
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
                "A positive service access-token TTL is required when token issuance is enabled",
                exception.getMessage());
    }

    @Test
    void rejectsNonPositiveUserTokenTtlWhenEnabled() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setUserAccessTokenTtl(Duration.ZERO);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::validateRequiredValues);

        assertEquals("User access-token TTL must be positive when configured", exception.getMessage());
    }

    @Test
    void userTokenTtlFallsBackToServiceTokenTtlWhenNotConfigured() {
        AuthorizationServerProperties properties = enabledProperties();
        properties.setUserAccessTokenTtl(null);

        assertEquals(properties.requiredServiceAccessTokenTtl(), properties.requiredUserAccessTokenTtl());
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
        properties.setUserAccessTokenTtl(Duration.ofMinutes(5));
        return properties;
    }
}

package com.quizopia.gateway.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayBrowserOriginPropertiesTest {
    @Test
    void acceptsOnlyExplicitUniqueHttpOrigins() {
        var properties =
                new GatewayBrowserOriginProperties(List.of("http://localhost:3000", "https://quizopia.example"));

        assertEquals(List.of("http://localhost:3000", "https://quizopia.example"), properties.allowedOrigins());
    }

    @Test
    void rejectsEmptyWildcardNullMalformedAndDuplicateOrigins() {
        assertThrows(IllegalArgumentException.class, () -> new GatewayBrowserOriginProperties(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GatewayBrowserOriginProperties(List.of("*")));
        assertThrows(IllegalArgumentException.class, () -> new GatewayBrowserOriginProperties(List.of("null")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GatewayBrowserOriginProperties(List.of("https://quizopia.example/path")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GatewayBrowserOriginProperties(
                        List.of("https://quizopia.example", "https://quizopia.example")));
    }
}

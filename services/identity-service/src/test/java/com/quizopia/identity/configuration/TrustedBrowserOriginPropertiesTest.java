package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedBrowserOriginPropertiesTest {
    @Test
    void trustsOnlyExactConfiguredOrigins() {
        var properties =
                new TrustedBrowserOriginProperties(List.of("http://localhost:3000", "https://quizopia.example"));

        assertTrue(properties.trusts("http://localhost:3000"));
        assertTrue(properties.trusts("https://quizopia.example"));
        assertFalse(properties.trusts("https://quizopia.example.attacker.test"));
        assertFalse(properties.trusts("https://sub.quizopia.example"));
        assertFalse(properties.trusts("null"));
        assertFalse(properties.trusts(null));
    }

    @Test
    void rejectsUnsafeOrNonOriginConfiguration() {
        for (List<String> invalid : List.of(
                List.<String>of(),
                List.of("*"),
                List.of("null"),
                List.of(" http://localhost:3000"),
                List.of("http://localhost:3000/"),
                List.of("https://quizopia.example/path"),
                List.of("https://quizopia.example?query=true"),
                List.of("ftp://quizopia.example"),
                List.of("https://quizopia.example", "https://quizopia.example"))) {
            assertThrows(IllegalArgumentException.class, () -> new TrustedBrowserOriginProperties(invalid));
        }
    }
}

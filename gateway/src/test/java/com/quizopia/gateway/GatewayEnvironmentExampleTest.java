package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GatewayEnvironmentExampleTest {
    @Test
    void localIdentityIssuerAndJwksFormOneCoherentTopology() throws IOException {
        Map<String, String> values = Files.readAllLines(Path.of(".env.example")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));

        assertEquals("http://localhost:8081", values.get("IDENTITY_ISSUER"));
        assertEquals("http://localhost:8081/oauth2/jwks", values.get("IDENTITY_JWKS_URI"));
    }
}

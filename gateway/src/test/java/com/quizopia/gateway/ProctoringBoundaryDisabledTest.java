package com.quizopia.gateway;

import static com.quizopia.gateway.AdmissionTestTokens.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ProctoringBoundaryDisabledTest.Keys.class)
class ProctoringBoundaryDisabledTest {
    @Value("${local.server.port}")
    int port;

    WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void reviewedAdaptersDefaultUnavailableWhileUnknownAndPrivateRoutesStayClosed() {
        http().post()
                .uri("/api/proctoring/monitoring/sessions")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        for (String path : List.of(
                "/api/proctoring/sessions",
                "/api/proctoring/token",
                "/internal/proctoring/attempts/" + ACTOR + "/authority"))
            http().post()
                    .uri(path)
                    .headers(h -> h.setBearerAuth(user()))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
    }

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        ReactiveJwtDecoder decoder() throws Exception {
            var d = NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return d;
        }
    }
}

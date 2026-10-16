package com.quizopia.gateway;

import static com.quizopia.gateway.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.gateway.security.admission.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "quizopia.proctoring.enabled=true")
@ActiveProfiles("test")
@Import(MediaBoundaryTest.Keys.class)
class MediaBoundaryTest {
    static final String BASE = "/api/proctoring/monitoring/sessions/" + ACTOR + "/media-grants";

    @Value("${local.server.port}")
    int port;

    @Test
    void fourExactMediaOperationsRemainUnavailableIndependentlyOfEnabledM1() {
        var http =
                WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        http.post()
                .uri(BASE)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        for (String suffix : List.of("/" + ACTOR + "/renew", "/" + ACTOR + "/revoke"))
            http.post()
                    .uri(BASE + suffix)
                    .headers(h -> h.setBearerAuth(user()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503);
        http.get()
                .uri(BASE + "/" + ACTOR)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        http.post()
                .uri(BASE + "/arbitrary")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void onlyCanonicalMediaInventoryRequiresCurrentUserAdmission() {
        for (var op : List.of(
                "POST " + BASE,
                "GET " + BASE + "/" + ACTOR,
                "POST " + BASE + "/" + ACTOR + "/renew",
                "POST " + BASE + "/" + ACTOR + "/revoke")) {
            var pair = op.split(" ");
            assertTrue(MediaRoutes.matches(pair[0], pair[1]));
            assertEquals("STUDENT", AdmissionRoutes.requiredRole(pair[0], pair[1]));
        }
        for (String path : List.of(
                BASE + "/",
                BASE.replace(ACTOR, "ABCDEF00-0000-4000-8000-000000000001"),
                BASE + "/" + ACTOR + "/revoke/",
                BASE + "/" + ACTOR + ";x=1",
                "/internal/media",
                "/api/proctoring/token"))
            for (String method : List.of("GET", "POST", "DELETE")) {
                assertFalse(MediaRoutes.matches(method, path), path);
                assertNull(AdmissionRoutes.requiredRole(method, path));
            }
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

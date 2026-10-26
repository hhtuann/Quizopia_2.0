package com.quizopia.gateway;

import static com.quizopia.gateway.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "IDENTITY_JWKS_URI=http://127.0.0.1:1/oauth2/jwks",
            "IDENTITY_ISSUER=https://identity.admission.test",
            "IDENTITY_SERVICE_URL=http://127.0.0.1:1",
            "QUIZ_SERVICE_URL=http://127.0.0.1:1",
            "CLASSROOM_SERVICE_URL=http://127.0.0.1:1",
            "ASSESSMENT_SERVICE_URL=http://127.0.0.1:1",
            "COMMUNITY_SERVICE_URL=http://127.0.0.1:1",
            "PROCTORING_SERVICE_URL=http://127.0.0.1:1",
            "AI_SERVICE_URL=http://127.0.0.1:1",
            "REDIS_HOST=127.0.0.1",
            "REDIS_PORT=1",
            "GATEWAY_ALLOWED_ORIGINS=https://quizopia.example",
            "SERVER_PORT=0"
        })
@ActiveProfiles("prod")
@Import(GatewayAdmissionIntegrationTest.Keys.class)
class GatewayProductionRoutesTest {
    @Autowired
    RouteDefinitionLocator definitions;

    @Value("${local.server.port}")
    int port;

    @Test
    void effectiveProdRoutesContainExactClassroomMethodsWithoutRewrite() {
        var routes = definitions.getRouteDefinitions().collectList().block();
        for (String id : java.util.List.of("classroom-create", "classroom-owned-read", "classroom-owned-list")) {
            var route = routes.stream()
                    .filter(r -> r.getId().equals(id))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, route.getPredicates().size());
            assertTrue(route.getFilters().isEmpty());
            assertTrue(route.getPredicates().stream()
                    .anyMatch(p -> p.getName().equals("Path")
                            && p.getArgs()
                                    .containsValue(
                                            !id.equals("classroom-owned-read")
                                                    ? "/classrooms"
                                                    : "/classrooms/{classroomId}")));
            assertTrue(route.getPredicates().stream()
                    .anyMatch(p -> p.getName().equals("Method")
                            && p.getArgs().containsValue(id.equals("classroom-create") ? "POST" : "GET")));
        }
    }

    @Test
    void disabledAdmissionInProdCannotSelectJwtOnlyAuthorization() {
        var http =
                WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        http.post()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        http.get()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        http.get()
                .uri("/classrooms/" + ACTOR)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        http.put()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void realtimeTicketAndWebSocketAreDefaultOffAtTheIngressBoundary() {
        var http =
                WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        http.post()
                .uri("/api/realtime/tickets")
                .header("Origin", "https://quizopia.example")
                .headers(headers -> headers.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", true);
        http.get()
                .uri("/ws/realtime")
                .header("Origin", "https://quizopia.example")
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Protocol", "quizopia.realtime.v1")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Sec-WebSocket-Version", "13")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", true);
    }

    static java.util.List<String[]> studentRoutes() {
        String a = "/api/assessments/attempts/" + ACTOR, p = "/api/assessments/publications/" + ACTOR;
        return java.util.List.of(
                new String[] {"POST", p + "/attempts/start"},
                new String[] {"POST", p + "/attempts/retake"},
                new String[] {"GET", a},
                new String[] {"POST", a + "/questions/1/answer"},
                new String[] {"POST", a + "/submit"},
                new String[] {"POST", a + "/abandon"},
                new String[] {"GET", a + "/result"},
                new String[] {"GET", a + "/review"},
                new String[] {"GET", p + "/aggregate-score"});
    }

    @Test
    void everyStudentRouteIsDefaultDisabledAndFutureRoutesStayDenied() {
        for (var op : studentRoutes()) {
            WebTestClient.bindToServer()
                    .baseUrl("http://127.0.0.1:" + port)
                    .build()
                    .method(org.springframework.http.HttpMethod.valueOf(op[0]))
                    .uri(op[1])
                    .headers(h -> h.setBearerAuth(user()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
        }
    }
}

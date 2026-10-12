package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.infrastructure.*;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Real HTTP/OAuth stubs supplement, rather than replace, PublicationTopologyTest. */
class PublicationSourceClientTest {
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> BODY = new AtomicReference<>();
    static final AtomicBoolean SLOW = new AtomicBoolean();
    static final UUID CLASSROOM = UUID.randomUUID(), ACTOR = UUID.randomUUID();
    static final DisposableServer SERVER = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                if (request.uri().equals("/oauth2/token"))
                    return request.receive()
                            .then(response.header("Content-Type", "application/json")
                                    .sendString(
                                            Mono.just(
                                                    "{\"access_token\":\"service-fixture-only\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"classroom.teacher-authority.read\"}"))
                                    .then());
                assertEquals(
                        "Bearer service-fixture-only", request.requestHeaders().get("Authorization"));
                assertEquals("user-fixture-only", request.requestHeaders().get("X-Quizopia-User-Evidence"));
                assertNull(request.requestHeaders().get("Cookie"));
                return response.status(STATUS.get())
                        .header("Content-Type", "application/json")
                        .sendString(SLOW.get() ? Mono.never() : Mono.just(BODY.get()));
            })
            .bindNow();
    PublicationSourceClient client;

    @BeforeEach
    void start() {
        STATUS.set(200);
        SLOW.set(false);
        BODY.set("{\"classroomId\":\"" + CLASSROOM + "\",\"actorId\":\"" + ACTOR + "\"}");
        var admission = new AdmissionProperties();
        admission.setEnabled(true);
        admission.setAllowInsecureLoopback(true);
        admission.setIdentityBaseUrl(URI.create("http://127.0.0.1:" + SERVER.port()));
        admission.setClientId("assessment-service");
        admission.setClientSecret("fixture-only");
        admission.setConnectTimeout(Duration.ofMillis(100));
        admission.setResponseTimeout(Duration.ofMillis(300));
        admission.setTotalTimeout(Duration.ofSeconds(1));
        var sources = new SourceProperties();
        sources.setEnabled(true);
        sources.setQuizBaseUrl(admission.getIdentityBaseUrl());
        sources.setClassroomBaseUrl(admission.getIdentityBaseUrl());
        client = new PublicationSourceClient(sources, admission);
    }

    @AfterEach
    void close() {
        client.close();
    }

    @AfterAll
    static void stop() {
        SERVER.disposeNow();
    }

    @Test
    void confirmationRequiresExactActorAndClassroom() {
        assertDoesNotThrow(() -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"));
        BODY.set("{\"classroomId\":\"" + CLASSROOM + "\",\"actorId\":\"" + UUID.randomUUID() + "\"}");
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"))
                        .status());
    }

    @ParameterizedTest
    @CsvSource({
        "401,SERVICE_AUTHENTICATION_INVALID,503",
        "403,SERVICE_CALLER_FORBIDDEN,503",
        "401,USER_EVIDENCE_INVALID,401",
        "403,USER_ROLE_FORBIDDEN,403",
        "404,CLASSROOM_NOT_ACCESSIBLE,404",
        "503,AUTHORITY_UNAVAILABLE,503"
    })
    void sanitizedFailuresDistinguishServiceFromUser(int upstream, String code, int expected) {
        STATUS.set(upstream);
        BODY.set("{\"code\":\"" + code + "\"}");
        var failure =
                assertThrows(PublicationFailure.class, () -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"));
        assertEquals(expected, failure.status());
        assertFalse(failure.toString().contains("fixture"));
        assertNull(failure.getCause());
    }

    @Test
    void malformedRedirectAndTimeoutCannotSucceed() {
        BODY.set("{\"actorId\":null}");
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"))
                        .status());
        STATUS.set(302);
        BODY.set("{}");
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"))
                        .status());
        STATUS.set(200);
        SLOW.set(true);
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> client.teacher(CLASSROOM, ACTOR, "user-fixture-only"))
                        .status());
    }

    @Test
    void arbitraryOrInsecureDestinationsFailConfiguration() {
        var sources = new SourceProperties();
        sources.setEnabled(true);
        sources.setQuizBaseUrl(URI.create("http://example.test"));
        sources.setClassroomBaseUrl(URI.create("https://classroom.test"));
        assertThrows(IllegalStateException.class, () -> sources.validate(true));
        sources.setQuizBaseUrl(URI.create("https://quiz.test/path"));
        assertThrows(IllegalStateException.class, () -> sources.validate(false));
    }
}

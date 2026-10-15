package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.media.MediaProperties;
import com.quizopia.proctoring.media.infrastructure.LiveKitMediaProvider;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import livekit.LivekitRoom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Official SDK wire contract against an explicit HTTP double, never Cloud qualification evidence. */
class LiveKitMediaProviderWireTest {
    static final String SECRET = "synthetic-sdk-wire-secret-at-least-32-bytes";

    MediaProperties properties(int port, boolean qualified) {
        var properties = new MediaProperties();
        properties.setApiUrl(URI.create("http://127.0.0.1:" + port));
        properties.setApiKey("synthetic-wire-key");
        properties.setApiSecret(SECRET);
        if (qualified) {
            properties.setRevocationMode(MediaProperties.RevocationMode.CLOUD_STRICT);
            properties.setQualificationReference("synthetic-protocol-fixture-not-provider-qualification");
        }
        return properties;
    }

    @Test
    void administrativeBearerHasExplicitShortLivedClaimsForEveryActualSdkRequest() throws Exception {
        var tokens = new ArrayList<String>();
        var paths = new ArrayList<String>();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    paths.add(request.uri());
                    tokens.add(request.requestHeaders().get("Authorization").substring("Bearer ".length()));
                    return response.header("Content-Type", "application/protobuf")
                            .sendByteArray(Mono.just(new byte[0]));
                })
                .bindNow();
        var before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        try (var provider = new LiveKitMediaProvider(properties(server.port(), true))) {
            provider.ensureRoom("synthetic-room");
            provider.deleteRoom("synthetic-room");
            provider.revoke("synthetic-room", "synthetic-participant", Instant.now());
            provider.ensureRoom("synthetic-room");
            assertEquals(
                    List.of(
                            "/twirp/livekit.RoomService/CreateRoom",
                            "/twirp/livekit.RoomService/DeleteRoom",
                            "/twirp/livekit.RoomService/RemoveParticipant",
                            "/twirp/livekit.RoomService/CreateRoom"),
                    paths);
            var ids = new HashSet<String>();
            for (int i = 0; i < tokens.size(); i++) {
                var jwt = SignedJWT.parse(tokens.get(i));
                assertTrue(jwt.verify(new MACVerifier(SECRET)));
                var claims = jwt.getJWTClaimsSet();
                assertNotNull(
                        claims.getIssueTime(),
                        "Administrative iat must be explicit; observed remaining lifetime seconds="
                                + Duration.between(
                                                Instant.now(),
                                                claims.getExpirationTime().toInstant())
                                        .toSeconds());
                assertEquals("synthetic-wire-key", claims.getIssuer());
                var issued = claims.getIssueTime().toInstant();
                assertFalse(issued.isBefore(before));
                assertFalse(issued.isAfter(Instant.now()));
                assertEquals(issued, claims.getNotBeforeTime().toInstant());
                assertEquals(
                        30,
                        Duration.between(issued, claims.getExpirationTime().toInstant())
                                .toSeconds());
                assertNull(claims.getSubject());
                assertNotNull(claims.getJWTID());
                assertTrue(ids.add(claims.getJWTID()), "Each SDK operation must receive a newly minted bearer");
                assertEquals(
                        i == 2 ? Map.of("roomAdmin", true, "room", "synthetic-room") : Map.of("roomCreate", true),
                        claims.getJSONObjectClaim("video"));
            }
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void explicitRevocationCutoffUsesUnixSecondsInActualSdkProtobufRequest() throws Exception {
        var payload = new AtomicReference<byte[]>();
        var path = new AtomicReference<String>();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    path.set(request.uri());
                    return request.receive().aggregate().asByteArray().flatMap(bytes -> {
                        payload.set(bytes);
                        return response.header("Content-Type", "application/protobuf")
                                .sendByteArray(Mono.just(new byte[0]))
                                .then();
                    });
                })
                .bindNow();
        try (var provider = new LiveKitMediaProvider(properties(server.port(), true))) {
            var cutoff = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            provider.revoke("synthetic-room", "synthetic-participant", cutoff);
            assertEquals("/twirp/livekit.RoomService/RemoveParticipant", path.get());
            var request = LivekitRoom.RoomParticipantIdentity.parseFrom(payload.get());
            assertEquals("synthetic-room", request.getRoom());
            assertEquals("synthetic-participant", request.getIdentity());
            assertEquals(cutoff.getEpochSecond(), request.getRevokeTokenTs());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void unqualifiedProviderCannotSendRevocationRequest() {
        var requests = new AtomicInteger();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    requests.incrementAndGet();
                    return response.send();
                })
                .bindNow();
        try (var provider = new LiveKitMediaProvider(properties(server.port(), false))) {
            assertFalse(provider.qualified());
            var failure =
                    assertThrows(MonitoringFailure.class, () -> provider.revoke("room", "participant", Instant.now()));
            assertEquals(503, failure.status());
            assertEquals("MEDIA_REVOCATION_UNVERIFIED", failure.code());
            assertEquals(0, requests.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void applicationAdapterSignsCameraOnlyStudentAndSubscribeOnlyTeacherWithExactExpiry() throws Exception {
        try (var provider = new LiveKitMediaProvider(properties(1, false))) {
            var expiry = Instant.now().plusSeconds(15).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            for (String role : List.of("STUDENT", "TEACHER")) {
                var token = SignedJWT.parse(provider.token("opaque-room", "opaque-identity", role, expiry));
                assertTrue(token.verify(new MACVerifier(SECRET)));
                var claims = token.getJWTClaimsSet();
                var video = claims.getJSONObjectClaim("video");
                assertEquals(expiry, claims.getExpirationTime().toInstant());
                assertEquals(expiry.minusSeconds(31), claims.getNotBeforeTime().toInstant());
                assertEquals("opaque-identity", claims.getSubject());
                assertEquals("opaque-room", video.get("room"));
                assertEquals(true, video.get("roomJoin"));
                assertEquals(role.equals("STUDENT"), video.get("canPublish"));
                assertEquals(role.equals("TEACHER"), video.get("canSubscribe"));
                assertEquals(false, video.get("canPublishData"));
                assertNotEquals(true, video.get("roomAdmin"));
                assertNotEquals(true, video.get("roomCreate"));
                assertNotEquals(true, video.get("roomList"));
                assertNotEquals(true, video.get("roomRecord"));
                assertNotEquals(true, video.get("canUpdateOwnMetadata"));
                if (role.equals("STUDENT")) assertEquals(List.of("camera"), video.get("canPublishSources"));
            }
        }
    }

    @Test
    void invalidRoleAndExpiredTokenCannotBeSignedAsAuthorizedGrant() {
        try (var provider = new LiveKitMediaProvider(properties(1, false))) {
            for (String role : List.of("ADMIN", "STUDENT,TEACHER", "student")) {
                var failure = assertThrows(
                        MonitoringFailure.class,
                        () -> provider.token(
                                "room", "participant", role, Instant.now().plusSeconds(10)));
                assertEquals(409, failure.status());
                assertEquals("MEDIA_GRANT_ENDED", failure.code());
            }
            assertThrows(
                    MonitoringFailure.class,
                    () -> provider.token(
                            "room", "participant", "STUDENT", Instant.now().minusSeconds(1)));
            assertThrows(
                    MonitoringFailure.class,
                    () -> provider.token(
                            "room", "participant", "STUDENT", Instant.now().plusSeconds(31)));
        }
    }

    @Test
    void upstreamFailureIsSanitizedAndDoesNotRetryOrExposeProviderBody() {
        var count = new AtomicInteger();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    count.incrementAndGet();
                    return response.status(500)
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"msg\":\"synthetic-provider-sensitive-body\"}"));
                })
                .bindNow();
        try (var provider = new LiveKitMediaProvider(properties(server.port(), true))) {
            var failure = assertThrows(MonitoringFailure.class, () -> provider.ensureRoom("opaque-room"));
            assertEquals(503, failure.status());
            assertEquals("MEDIA_UNAVAILABLE", failure.code());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("sensitive"));
            assertEquals(1, count.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void administrativeBearerAndCredentialsStayOutOfFailureAndLogs(CapturedOutput output) {
        var token = new AtomicReference<String>();
        var count = new AtomicInteger();
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    count.incrementAndGet();
                    token.set(request.requestHeaders().get("Authorization").substring("Bearer ".length()));
                    assertFalse(request.uri().contains(token.get()));
                    return response.status(500)
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"token\":\"" + token.get() + "\",\"secret\":\"" + SECRET + "\"}"));
                })
                .bindNow();
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var originalLevel = root.getLevel();
        try (var provider = new LiveKitMediaProvider(properties(server.port(), true))) {
            root.setLevel(ch.qos.logback.classic.Level.DEBUG);
            var failure = assertThrows(MonitoringFailure.class, () -> provider.deleteRoom("synthetic-room"));
            assertEquals(503, failure.status());
            assertEquals("MEDIA_UNAVAILABLE", failure.code());
            assertNull(failure.getCause());
            assertNotNull(token.get());
            assertFalse(failure.getMessage().contains(token.get()));
            assertFalse(failure.getMessage().contains(SECRET));
            assertFalse(output.getAll().contains(token.get()));
            assertFalse(output.getAll().contains(SECRET));
            assertEquals(1, count.get(), "An uncertain administrative request must not be implicitly retried");
        } finally {
            root.setLevel(originalLevel);
            server.disposeNow();
        }
    }

    @Test
    void roomDeletionAcceptsOnlyExactNotFoundResponseAfterUncertainEarlierDeletion() {
        for (String body : List.of(
                "{\"code\":\"not_found\",\"msg\":\"synthetic missing room\"}",
                "{\"code\":\"permission_denied\"}",
                "<html>missing</html>",
                "{\"code\":\"not_found\",\"code\":\"permission_denied\"}",
                "{\"code\":\"not_found\"} {}",
                "x".repeat(5000))) {
            var requests = new AtomicInteger();
            var server = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> {
                        requests.incrementAndGet();
                        return response.status(404)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just(body));
                    })
                    .bindNow();
            try (var provider = new LiveKitMediaProvider(properties(server.port(), true))) {
                if (body.equals("{\"code\":\"not_found\",\"msg\":\"synthetic missing room\"}")) {
                    assertDoesNotThrow(() -> provider.deleteRoom("opaque-room"));
                } else {
                    var failure = assertThrows(MonitoringFailure.class, () -> provider.deleteRoom("opaque-room"));
                    assertEquals(503, failure.status());
                    assertEquals("MEDIA_UNAVAILABLE", failure.code());
                    assertNull(failure.getCause());
                }
                assertEquals(1, requests.get());
            } finally {
                server.disposeNow();
            }
        }
    }
}

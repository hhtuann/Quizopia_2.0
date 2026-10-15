package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.proctoring.media.MediaProperties;
import com.quizopia.proctoring.media.infrastructure.LiveKitMediaProvider;
import io.livekit.server.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import livekit.LivekitModels;
import livekit.LivekitRtc;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;

/** Actual SDK + owned SFU signaling. No browser, camera, microphone or WebRTC media is exercised. */
@Testcontainers
class LiveKitSdkIntegrationTest {
    static final String API_KEY = "synthetic-" + UUID.randomUUID();
    static final byte[] SECRET_BYTES = new byte[32];

    static {
        new SecureRandom().nextBytes(SECRET_BYTES);
    }

    static final String SECRET = Base64.getEncoder().encodeToString(SECRET_BYTES);
    static final String CONFIG =
            "port: 7880\nrtc:\n  tcp_port: 7881\n  port_range_start: 50000\n  port_range_end: 50010\n  use_external_ip: false\nkeys:\n  "
                    + API_KEY + ": " + SECRET + "\nlogging:\n  level: warn\n";

    @Container
    static final GenericContainer<?> SERVER = new GenericContainer<>("livekit/livekit-server:v1.9.0")
            .withExposedPorts(7880)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(7880))))
            .withCopyToContainer(
                    Transferable.of(CONFIG.getBytes(StandardCharsets.UTF_8), 0600), "/tmp/quizopia-livekit.yaml")
            .withCommand("--config", "/tmp/quizopia-livekit.yaml", "--bind", "0.0.0.0")
            .waitingFor(Wait.forHttp("/").forPort(7880).forStatusCode(200));

    final Set<String> rooms = new HashSet<>();

    String origin() {
        return "http://127.0.0.1:" + SERVER.getMappedPort(7880);
    }

    RoomServiceClient admin() {
        return RoomServiceClient.createClient(origin(), API_KEY, SECRET);
    }

    String room() throws Exception {
        String name = "quizopia-synthetic-" + UUID.randomUUID();
        rooms.add(name);
        var response = admin().createRoom(name, 30, 4).execute();
        assertTrue(response.isSuccessful(), "Actual LiveKit room creation must succeed");
        assertNotNull(response.body());
        return name;
    }

    @AfterEach
    void cleanupOwnedRooms() throws Exception {
        for (String room : rooms) admin().deleteRoom(room).execute();
    }

    AccessToken token(String room, String identity, boolean teacher) {
        var token = new AccessToken(API_KEY, SECRET);
        token.setIdentity(identity);
        token.setTtl(30_000); // Official Java/Kotlin SDK setter is milliseconds.
        token.addGrants(
                new RoomJoin(true),
                new RoomName(room),
                new CanPublish(!teacher),
                new CanSubscribe(teacher),
                new CanPublishData(false),
                new CanUpdateOwnMetadata(false),
                new RoomAdmin(false));
        if (!teacher) token.addGrants(new CanPublishSources(List.of("camera")));
        return token;
    }

    SignedJWT verified(String value) throws Exception {
        var token = SignedJWT.parse(value);
        assertTrue(
                token.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))),
                "Signature must verify with the owned provider secret");
        return token;
    }

    static final class Signaling implements WebSocket.Listener, AutoCloseable {
        final CompletableFuture<LivekitRtc.JoinResponse> joined = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            frame.writeBytes(bytes);
            if (last) {
                try {
                    var response = LivekitRtc.SignalResponse.parseFrom(frame.toByteArray());
                    if (response.hasJoin()) joined.complete(response.getJoin());
                } catch (Exception error) {
                    joined.completeExceptionally(new IllegalStateException("Invalid owned SFU signaling frame"));
                }
                frame.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            closed.complete(status);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            joined.completeExceptionally(new IllegalStateException("Owned SFU signaling failed"));
        }

        @Override
        public void close() {
            if (socket != null) socket.abort();
        }
    }

    Signaling connect(String token) throws Exception {
        var signaling = new Signaling();
        var uri = URI.create(origin().replace("http://", "ws://") + "/rtc?access_token=" + token
                + "&protocol=15&auto_subscribe=0&sdk=java&version=fixture");
        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(uri, signaling)
                .get(5, TimeUnit.SECONDS);
        return signaling;
    }

    @Test
    void actualSdkCreatesIdempotentlyListsAndDeletesOwnedRoom() throws Exception {
        String room = room();
        var first = admin().listRooms(List.of(room)).execute();
        assertTrue(first.isSuccessful());
        assertEquals(1, first.body().size());
        var repeated = admin().createRoom(room, 30, 4).execute();
        assertTrue(repeated.isSuccessful());
        assertEquals(first.body().getFirst().getSid(), repeated.body().getSid());
        var participants = admin().listParticipants(room).execute();
        assertTrue(participants.isSuccessful());
        assertTrue(participants.body().isEmpty());
        assertTrue(admin().deleteRoom(room).execute().isSuccessful());
        assertTrue(admin().listRooms(List.of(room)).execute().body().isEmpty());
    }

    @Test
    void applicationAdapterShortAdministrativeBearersWorkAgainstActualProvider() throws Exception {
        String room = "quizopia-synthetic-bounded-admin-" + UUID.randomUUID();
        rooms.add(room);
        var properties = new MediaProperties();
        properties.setApiUrl(URI.create(origin()));
        properties.setApiKey(API_KEY);
        properties.setApiSecret(SECRET);
        // Synthetic configuration exercises request mechanics, not strict Cloud qualification.
        properties.setRevocationMode(MediaProperties.RevocationMode.CLOUD_STRICT);
        properties.setQualificationReference("synthetic-self-hosted-sdk-fixture-only");
        try (var provider = new LiveKitMediaProvider(properties)) {
            provider.ensureRoom(room);
            assertEquals(1, admin().listRooms(List.of(room)).execute().body().size());
            String identity = "synthetic-adapter-participant";
            try (var participant = connect(token(room, identity, false).toJwt())) {
                assertEquals(
                        identity,
                        participant
                                .joined
                                .get(5, TimeUnit.SECONDS)
                                .getParticipant()
                                .getIdentity());
                provider.revoke(room, identity, Instant.now());
                participant.closed.get(5, TimeUnit.SECONDS);
            }
            provider.deleteRoom(room);
            assertTrue(admin().listRooms(List.of(room)).execute().body().isEmpty());
        }
    }

    @Test
    void studentTokenIsCryptographicallyCameraOnlyAndBoundedToOneIdentityAndRoom() throws Exception {
        String room = room();
        String identity = "synthetic-student-" + UUID.randomUUID();
        var token = verified(token(room, identity, false).toJwt());
        var claims = token.getJWTClaimsSet();
        var video = claims.getJSONObjectClaim("video");
        assertEquals(API_KEY, claims.getIssuer());
        assertEquals(identity, claims.getSubject());
        assertEquals(room, video.get("room"));
        assertEquals(true, video.get("roomJoin"));
        assertEquals(true, video.get("canPublish"));
        assertEquals(false, video.get("canSubscribe"));
        assertEquals(false, video.get("canPublishData"));
        assertEquals(false, video.get("canUpdateOwnMetadata"));
        assertEquals(false, video.get("roomAdmin"));
        assertEquals(List.of("camera"), video.get("canPublishSources"));
        assertTrue(claims.getExpirationTime().toInstant().isBefore(Instant.now().plusSeconds(31)));
        assertTrue(claims.getExpirationTime().toInstant().isAfter(Instant.now().plusSeconds(20)));
    }

    @Test
    void actualStudentSignalingPermissionHasCameraOnlyNoSubscribeOrData() throws Exception {
        String room = room();
        try (var signaling = connect(token(room, "synthetic-student", false).toJwt())) {
            var joined = signaling.joined.get(5, TimeUnit.SECONDS);
            assertEquals(room, joined.getRoom().getName());
            var permission = joined.getParticipant().getPermission();
            assertTrue(permission.getCanPublish());
            assertFalse(permission.getCanSubscribe());
            assertFalse(permission.getCanPublishData());
            assertFalse(permission.getCanUpdateMetadata());
            assertEquals(List.of(LivekitModels.TrackSource.CAMERA), permission.getCanPublishSourcesList());
        }
    }

    @Test
    void actualTeacherSignalingIsSubscribeOnlyAndParticipantTokenCannotAdministerRooms() throws Exception {
        String room = room();
        String grant = token(room, "synthetic-teacher", true).toJwt();
        try (var signaling = connect(grant)) {
            var joined = signaling.joined.get(5, TimeUnit.SECONDS);
            var permission = joined.getParticipant().getPermission();
            assertTrue(permission.getCanSubscribe());
            assertFalse(permission.getCanPublish());
            assertFalse(permission.getCanPublishData());
        }
        var response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(origin() + "/twirp/livekit.RoomService/ListRooms"))
                                .header("Authorization", "Bearer " + grant)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertTrue(
                Set.of(401, 403).contains(response.statusCode()),
                "Room-join grant must not become an administrative credential");
    }

    @Test
    void wrongProviderSecretCannotUseActualRoomApi() throws Exception {
        var unauthorized = RoomServiceClient.createClient(
                origin(), API_KEY, Base64.getEncoder().encodeToString(new byte[32]));
        var result = unauthorized.listRooms().execute();
        assertFalse(result.isSuccessful());
        assertTrue(Set.of(401, 403).contains(result.code()));
    }

    @Test
    void expiredParticipantTokenCannotEstablishActualSignaling() throws Exception {
        var token = token(room(), "synthetic-expired", false);
        token.setExpiration(Date.from(Instant.now().minusSeconds(120)));
        var failure = assertThrows(Exception.class, () -> connect(token.toJwt()));
        Throwable current = failure;
        while (current != null && !(current instanceof WebSocketHandshakeException)) current = current.getCause();
        assertInstanceOf(WebSocketHandshakeException.class, current);
        assertTrue(Set.of(401, 403)
                .contains(((WebSocketHandshakeException) current).getResponse().statusCode()));
    }

    @Test
    void selfHostedRemovalDoesNotInvalidateOldJoinTokenAndCannotProveStrictRevocation() throws Exception {
        String room = room();
        String identity = "synthetic-reconnect-" + UUID.randomUUID();
        String oldGrant = token(room, identity, false).toJwt();
        try (var first = connect(oldGrant)) {
            assertEquals(
                    identity,
                    first.joined.get(5, TimeUnit.SECONDS).getParticipant().getIdentity());
            assertTrue(admin().removeParticipant(room, identity).execute().isSuccessful());
            first.closed.get(5, TimeUnit.SECONDS);
            try (var reused = connect(oldGrant)) {
                assertEquals(
                        identity,
                        reused.joined.get(5, TimeUnit.SECONDS).getParticipant().getIdentity());
            }
        }
        // This observed provider limitation is why public media activation needs
        // separate accepted, deployed revocation evidence; a short TTL is not proof.
    }
}

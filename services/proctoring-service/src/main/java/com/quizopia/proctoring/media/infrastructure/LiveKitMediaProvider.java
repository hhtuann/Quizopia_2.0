package com.quizopia.proctoring.media.infrastructure;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.media.*;
import io.livekit.server.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.protobuf.ProtoConverterFactory;

/** Official pinned JVM SDK; no request logging, redirects, credentials in URLs or implicit retries. */
public final class LiveKitMediaProvider implements MediaProvider, AutoCloseable {
    private final MediaProperties properties;
    private final OkHttpClient http;
    // Engineering cap aligned with the existing maximum credential budget; not a product rule.
    private static final long ADMIN_BEARER_SECONDS = 30;

    private final RoomService roomService;

    public LiveKitMediaProvider(MediaProperties properties) {
        this.properties = properties;
        http = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
        roomService = new Retrofit.Builder()
                .baseUrl(properties.getApiUrl().toString().replaceAll("/+$", "") + "/")
                .addConverterFactory(ProtoConverterFactory.create())
                .client(http)
                .build()
                .create(RoomService.class);
    }

    @Override
    public boolean qualified() {
        return properties.qualified();
    }

    private RoomServiceClient administrativeClient(Map<String, Object> grants) {
        var issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // The pinned SDK's public explicit-token constructor avoids its six-hour AccessToken default.
        // Do not cache administrative clients or tokens; each operation mints its own bounded bearer.
        var bearer = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer(properties.getApiKey())
                        .issueTime(Date.from(issued))
                        .notBeforeTime(Date.from(issued))
                        .expirationTime(Date.from(issued.plusSeconds(ADMIN_BEARER_SECONDS)))
                        .jwtID(UUID.randomUUID().toString())
                        .claim("video", grants)
                        .build());
        try {
            bearer.sign(new MACSigner(properties.getApiSecret()));
        } catch (JOSEException invalidCredentials) {
            throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
        }
        return new RoomServiceClient(
                roomService, properties.getApiKey(), properties.getApiSecret(), bearer.serialize());
    }

    private void execute(Call<?> call) {
        execute(call, false);
    }

    private void execute(Call<?> call, boolean delete) {
        try {
            var response = call.execute();
            try {
                if (!response.isSuccessful()) {
                    boolean absent = false;
                    var body = response.errorBody();
                    if (delete
                            && response.code() == 404
                            && body != null
                            && body.contentType() != null
                            && body.contentType().type().equals("application")
                            && body.contentType().subtype().equals("json")) {
                        byte[] bytes = body.byteStream().readNBytes(1025);
                        if (bytes.length <= 1024) {
                            try {
                                var json = tools.jackson.databind.json.JsonMapper.builder()
                                        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .build()
                                        .readTree(bytes);
                                absent = json.isObject()
                                        && json.path("code").asString().equals("not_found");
                            } catch (RuntimeException invalid) {
                                // Unknown/HTML errors remain unavailable; never log the provider body.
                            }
                        }
                    }
                    if (!absent) throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
                }
            } finally {
                if (response.errorBody() != null) response.errorBody().close();
            }
        } catch (java.io.IOException error) {
            throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
        }
    }

    @Override
    public void ensureRoom(String room) {
        // LiveKit requires roomCreate for both create and delete and does not room-scope this grant.
        execute(administrativeClient(Map.of("roomCreate", true)).createRoom(room, 60, 2));
    }

    @Override
    public String token(String room, String identity, String role, Instant expiry) {
        if (!Set.of("STUDENT", "TEACHER").contains(role)
                || !expiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(Instant.now())
                || expiry.isAfter(Instant.now().plusSeconds(30))) throw new MonitoringFailure(409, "MEDIA_GRANT_ENDED");
        var token = new AccessToken(properties.getApiKey(), properties.getApiSecret());
        token.setIdentity(identity);
        token.setExpiration(Date.from(expiry));
        token.setNotBefore(Date.from(expiry.minusSeconds(31)));
        boolean student = role.equals("STUDENT");
        token.addGrants(
                new RoomJoin(true),
                new RoomName(room),
                new CanPublish(student),
                new CanSubscribe(!student),
                new CanPublishData(false));
        if (student) token.addGrants(new CanPublishSources(List.of("camera")));
        return token.toJwt();
    }

    @Override
    public void revoke(String room, String identity, Instant cutoff) {
        if (!qualified()) throw new MonitoringFailure(503, "MEDIA_REVOCATION_UNVERIFIED");
        // An explicit cutoff is required; the Cloud default's clock buffer is insufficient.
        execute(administrativeClient(Map.of("roomAdmin", true, "room", room))
                .removeParticipant(room, identity, cutoff.getEpochSecond()));
    }

    @Override
    public void deleteRoom(String room) {
        execute(administrativeClient(Map.of("roomCreate", true)).deleteRoom(room), true);
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}

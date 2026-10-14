package com.quizopia.proctoring.media.infrastructure;

import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.media.*;
import io.livekit.server.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Call;

/** Official pinned JVM SDK; no request logging, redirects, credentials in URLs or implicit retries. */
public final class LiveKitMediaProvider implements MediaProvider, AutoCloseable {
    private final MediaProperties properties;
    private final OkHttpClient http;
    private final RoomServiceClient rooms;

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
        rooms = RoomServiceClient.createClient(
                properties.getApiUrl().toString(),
                properties.getApiKey(),
                properties.getApiSecret(),
                () -> http,
                false);
    }

    @Override
    public boolean qualified() {
        return properties.qualified();
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
        execute(rooms.createRoom(room, 60, 2));
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
        execute(rooms.removeParticipant(room, identity, cutoff.getEpochSecond()));
    }

    @Override
    public void deleteRoom(String room) {
        execute(rooms.deleteRoom(room), true);
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}

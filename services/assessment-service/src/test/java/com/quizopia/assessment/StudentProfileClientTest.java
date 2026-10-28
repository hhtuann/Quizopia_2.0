package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.infrastructure.StudentProfileClient;
import com.quizopia.assessment.infrastructure.StudentProfileProperties;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Real HTTP/OAuth stubs prove the fail-open, bounded, strict-parse enrichment contract. */
class StudentProfileClientTest {
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> BODY = new AtomicReference<>("{}");
    static final AtomicReference<String> SENT = new AtomicReference<>();
    static final AtomicInteger BATCH_CALLS = new AtomicInteger();
    static final AtomicBoolean SLOW = new AtomicBoolean();
    static final AtomicBoolean PLAIN = new AtomicBoolean();
    static final UUID ONE = UUID.randomUUID(), TWO = UUID.randomUUID(), THREE = UUID.randomUUID();
    static final DisposableServer SERVER = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                if (request.uri().equals("/oauth2/token"))
                    return request.receive()
                            .then(response.header("Content-Type", "application/json")
                                    .sendString(
                                            Mono.just(
                                                    "{\"access_token\":\"service-fixture-only\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-profile.read\"}"))
                                    .then());
                if (request.uri().equals("/internal/security/user-profiles/batch")) {
                    assertEquals(
                            "Bearer service-fixture-only",
                            request.requestHeaders().get("Authorization"));
                    assertNull(request.requestHeaders().get("Cookie"));
                    return request.receive().aggregate().asString().flatMap(body -> {
                        SENT.set(body);
                        BATCH_CALLS.incrementAndGet();
                        return response.status(STATUS.get())
                                .header("Content-Type", PLAIN.get() ? "text/plain" : "application/json")
                                .sendString(SLOW.get() ? Mono.never() : Mono.just(BODY.get()))
                                .then();
                    });
                }
                return response.status(404).send().then();
            })
            .bindNow();
    StudentProfileClient client;

    @BeforeEach
    void start() {
        STATUS.set(200);
        SLOW.set(false);
        PLAIN.set(false);
        SENT.set(null);
        BATCH_CALLS.set(0);
        BODY.set("{}");
        client = new StudentProfileClient(properties(), admission());
    }

    static StudentProfileProperties properties() {
        var profiles = new StudentProfileProperties();
        profiles.setEnabled(true);
        profiles.setConnectTimeout(Duration.ofMillis(100));
        profiles.setResponseTimeout(Duration.ofMillis(300));
        profiles.setTotalTimeout(Duration.ofSeconds(1));
        return profiles;
    }

    static AdmissionProperties admission() {
        var admission = new AdmissionProperties();
        admission.setEnabled(true);
        admission.setAllowInsecureLoopback(true);
        admission.setIdentityBaseUrl(URI.create("http://127.0.0.1:" + SERVER.port()));
        admission.setClientId("assessment-service");
        admission.setClientSecret("fixture-only");
        admission.setConnectTimeout(Duration.ofMillis(100));
        admission.setResponseTimeout(Duration.ofMillis(300));
        admission.setTotalTimeout(Duration.ofSeconds(1));
        return admission;
    }

    static String items(String... entries) {
        return "{\"items\":[" + String.join(",", entries) + "]}";
    }

    static String entry(UUID id, String name) {
        return "{\"studentId\":\"" + id + "\",\"displayName\":\"" + name + "\"}";
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
    void resolvesKnownIdsWithExactStrictBodyAndServiceAuthentication() {
        BODY.set(items(entry(ONE, "Alpha One"), entry(TWO, "Beta Two")));
        var ids = new LinkedHashSet<UUID>(List.of(ONE, TWO, THREE));
        var names = client.displayNames(ids);
        assertEquals(Map.of(ONE, "Alpha One", TWO, "Beta Two"), names);
        assertEquals(1, BATCH_CALLS.get());
        assertEquals("{\"studentIds\":[\"" + ONE + "\",\"" + TWO + "\",\"" + THREE + "\"]}", SENT.get());
    }

    @Test
    void unknownForeignAndDuplicateIdsResolveToOmittedEntriesOnly() {
        BODY.set(items(entry(ONE, "Alpha"), entry(UUID.randomUUID(), "Foreign")));
        assertEquals(Map.of(ONE, "Alpha"), client.displayNames(Set.of(ONE, TWO)));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {500, 401, 403, 404, 429, 503})
    void upstreamStatusesFailOpenToEmptyWithoutThrowing(int status) {
        STATUS.set(status);
        BODY.set(items(entry(ONE, "Alpha")));
        assertEquals(Map.of(), client.displayNames(Set.of(ONE)));
    }

    @Test
    void malformedAndHostileBodiesNeverYieldEntries() {
        for (String body : List.of(
                "not-json",
                "{}{}",
                "{\"items\":[]}{\"items\":[{\"studentId\":\"" + ONE + "\",\"displayName\":\"Evil\"}]}",
                "{\"items\":[{\"studentId\":\"" + ONE + "\",\"studentId\":\"" + ONE + "\",\"displayName\":\"Evil\"}]}",
                "{\"items\":[{\"displayName\":\"NoId\"}]}",
                "{\"items\":[{\"studentId\":\"" + ONE + "\"}]}",
                "{\"items\":\"not-an-array\"}",
                "{\"items\":[],\"extra\":1}")) {
            BODY.set(body);
            assertEquals(Map.of(), client.displayNames(Set.of(ONE)), body);
        }
    }

    @Test
    void defensivelyInvalidEntriesAreDroppedWhileValidOnesSurvive() {
        String upper = ONE.toString().toUpperCase(Locale.ROOT), blank = "   ", long_ = "x".repeat(256);
        BODY.set(items(
                "{\"studentId\":\"" + upper + "\",\"displayName\":\"Upper\"}",
                "{\"studentId\":\"" + TWO + "\",\"displayName\":\"" + blank + "\"}",
                "{\"studentId\":\"" + THREE + "\",\"displayName\":\"" + long_ + "\"}",
                entry(ONE, "Alpha")));
        assertEquals(Map.of(ONE, "Alpha"), client.displayNames(Set.of(ONE, TWO, THREE)));
    }

    @Test
    void acceptsUnicodeDisplayNamesAndFullLengthLegacyUsernameFallbackWithoutTruncation() {
        String unicode = "\ud83d\ude00".repeat(100);
        String legacy = "u".repeat(255);
        BODY.set(items(entry(ONE, unicode), entry(TWO, legacy)));
        assertEquals(Map.of(ONE, unicode, TWO, legacy), client.displayNames(Set.of(ONE, TWO)));
    }

    @Test
    void maximalUnicodeLegacyBatchFitsBoundedResponseBuffer() {
        var entries = new ArrayList<String>();
        var expected = new HashMap<UUID, String>();
        String legacy = "\u754c".repeat(255);
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            expected.put(id, legacy);
            entries.add(entry(id, legacy));
        }
        BODY.set(items(entries.toArray(String[]::new)));
        assertEquals(expected, client.displayNames(expected.keySet()));
        assertEquals(1, BATCH_CALLS.get());
    }

    @Test
    void overfullResponseBatchIsRejected() {
        BODY.set(items(Collections.nCopies(101, entry(ONE, "Name")).toArray(String[]::new)));
        assertEquals(Map.of(), client.displayNames(Set.of(ONE)));
    }

    @Test
    void rejectsControlsAndUnpairedSurrogatesFromUpstream() {
        for (String name : List.of("Bad\\u0000Name", "Bad\\u0085Name", "Bad\\ud800Name")) {
            BODY.set(items(entry(ONE, name), entry(TWO, "Valid")));
            assertEquals(Map.of(TWO, "Valid"), client.displayNames(Set.of(ONE, TWO)));
        }
    }

    @Test
    void nonJsonContentTypeIsRejectedWithoutTrustingTheBody() {
        BODY.set(items(entry(ONE, "Alpha")));
        PLAIN.set(true);
        assertEquals(Map.of(), client.displayNames(Set.of(ONE)));
        PLAIN.set(false);
    }

    @Test
    void nonJsonContentTypeAndTimeoutFailOpenToEmpty() {
        BODY.set(items(entry(ONE, "Alpha")));
        SLOW.set(true);
        long start = System.nanoTime();
        assertEquals(Map.of(), client.displayNames(Set.of(ONE)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5000);
        SLOW.set(false);
    }

    @Test
    void oversizeEmptyAndNullSetsAreRefusedLocallyWithoutNetwork() {
        assertEquals(Map.of(), client.displayNames(Set.of()));
        assertEquals(Map.of(), client.displayNames(null));
        var many = new LinkedHashSet<UUID>();
        for (int i = 0; i < StudentProfileClient.MAX_BATCH + 1; i++) many.add(UUID.randomUUID());
        assertEquals(Map.of(), client.displayNames(many));
        assertEquals(0, BATCH_CALLS.get());
        assertNull(SENT.get());
    }

    @Test
    void disabledClientIsEmptyAndRequiresNothing() {
        var disabled = new StudentProfileProperties();
        try (var off = new StudentProfileClient(disabled, new AdmissionProperties())) {
            assertEquals(Map.of(), off.displayNames(Set.of(ONE)));
            assertEquals(0, BATCH_CALLS.get());
        }
    }

    @Test
    void invalidConfigurationIsRejected() {
        var badBounds = properties();
        badBounds.setTotalTimeout(null);
        assertThrows(IllegalStateException.class, () -> new StudentProfileClient(badBounds, admission()).close());
        var tooSlow = properties();
        tooSlow.setTotalTimeout(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> new StudentProfileClient(tooSlow, admission()).close());
        assertThrows(
                IllegalStateException.class,
                () -> new StudentProfileClient(properties(), new AdmissionProperties()).close());
    }
}

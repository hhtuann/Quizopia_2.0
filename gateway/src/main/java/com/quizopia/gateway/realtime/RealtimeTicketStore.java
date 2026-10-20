package com.quizopia.gateway.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

public final class RealtimeTicketStore {
    private static final Pattern TICKET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String PREFIX = "quizopia:gateway:realtime:v1:";
    private static final String ACTIVE_KEY = PREFIX + "active-tickets";
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[2])
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[5]) then return 2 end
            local actorCount = redis.call('INCR', KEYS[3])
            if actorCount == 1 then redis.call('PEXPIRE', KEYS[3], 60000) end
            if actorCount > tonumber(ARGV[6]) then
              redis.call('DECR', KEYS[3])
              return 1
            end
            local originCount = redis.call('INCR', KEYS[4])
            if originCount == 1 then redis.call('PEXPIRE', KEYS[4], 60000) end
            if originCount > tonumber(ARGV[7]) then
              redis.call('DECR', KEYS[4])
              redis.call('DECR', KEYS[3])
              return 1
            end
            local stored = redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[4], 'NX')
            if not stored then return 3 end
            redis.call('ZADD', KEYS[2], ARGV[3], KEYS[1])
            return 0
            """,
            Long.class);
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('GET', KEYS[1])
            if not value then return nil end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], KEYS[1])
            return value
            """,
            String.class);

    private final ReactiveStringRedisTemplate redis;
    private final RealtimeProperties properties;
    private final Clock clock;

    public RealtimeTicketStore(ReactiveStringRedisTemplate redis, RealtimeProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        properties.validate();
    }

    public Mono<IssuedTicket> issue(UUID actorId, String accessToken, String origin) {
        return Mono.defer(() -> {
                    Instant issuedAt = clock.instant();
                    Instant expiresAt = issuedAt.plus(properties.getTicketTtl());
                    String ticket = randomTicket();
                    String ticketKey = ticketKey(ticket);
                    String originHash = digest(origin);
                    String metadata =
                            encodeMetadata(actorId, tokenFingerprint(accessToken), origin, issuedAt, expiresAt);
                    List<String> keys = List.of(
                            ticketKey,
                            ACTIVE_KEY,
                            PREFIX + "issue:actor:" + actorId,
                            PREFIX + "issue:origin:" + originHash);
                    List<String> arguments = List.of(
                            metadata,
                            Long.toString(issuedAt.toEpochMilli()),
                            Long.toString(expiresAt.toEpochMilli()),
                            Long.toString(properties.getTicketTtl().toMillis()),
                            Integer.toString(properties.getMaximumOutstandingTickets()),
                            Integer.toString(properties.getTicketIssueRatePerActor()),
                            Integer.toString(properties.getTicketIssueRatePerOrigin()));
                    return redis.execute(ISSUE_SCRIPT, keys, arguments)
                            .single()
                            .flatMap(result -> switch (result.intValue()) {
                                case 0 ->
                                    Mono.just(new IssuedTicket(
                                            ticket,
                                            Math.toIntExact(
                                                    properties.getTicketTtl().toSeconds())));
                                case 1, 2 -> Mono.error(TicketStoreFailure.rateLimited());
                                default -> Mono.error(TicketStoreFailure.unavailable());
                            });
                })
                .onErrorMap(error -> error instanceof TicketStoreFailure ? error : TicketStoreFailure.unavailable());
    }

    public Mono<TicketMetadata> consume(String ticket) {
        if (ticket == null || !TICKET.matcher(ticket).matches()) {
            return Mono.error(TicketStoreFailure.invalid());
        }
        return redis.execute(CONSUME_SCRIPT, List.of(ticketKey(ticket), ACTIVE_KEY), List.of())
                .singleOrEmpty()
                .switchIfEmpty(Mono.error(TicketStoreFailure.invalid()))
                .map(this::decodeMetadata)
                .flatMap(metadata -> metadata.expiresAt().isAfter(clock.instant())
                        ? Mono.just(metadata)
                        : Mono.error(TicketStoreFailure.invalid()))
                .onErrorMap(error -> error instanceof TicketStoreFailure ? error : TicketStoreFailure.unavailable());
    }

    public static String tokenFingerprint(String accessToken) {
        return digest(accessToken);
    }

    private static String randomTicket() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    private static String ticketKey(String ticket) {
        return PREFIX + "ticket:" + digest(ticket);
    }

    private static String encodeMetadata(
            UUID actorId, String fingerprint, String origin, Instant issuedAt, Instant expiresAt) {
        return String.join(
                "|",
                "1",
                actorId.toString(),
                fingerprint,
                BASE64_URL.encodeToString(origin.getBytes(StandardCharsets.UTF_8)),
                Long.toString(issuedAt.toEpochMilli()),
                Long.toString(expiresAt.toEpochMilli()));
    }

    private TicketMetadata decodeMetadata(String encoded) {
        String[] fields = encoded.split("\\|", -1);
        if (fields.length != 6
                || !"1".equals(fields[0])
                || !FINGERPRINT.matcher(fields[2]).matches()) {
            throw TicketStoreFailure.unavailable();
        }
        try {
            UUID actorId = UUID.fromString(fields[1]);
            if (!actorId.toString().equals(fields[1])) throw TicketStoreFailure.unavailable();
            String origin = new String(BASE64_URL_DECODER.decode(fields[3]), StandardCharsets.UTF_8);
            Instant issuedAt = Instant.ofEpochMilli(Long.parseLong(fields[4]));
            Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[5]));
            if (origin.isBlank()
                    || !expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plus(properties.getTicketTtl()))) {
                throw TicketStoreFailure.unavailable();
            }
            return new TicketMetadata(actorId, fields[2], origin, issuedAt, expiresAt);
        } catch (IllegalArgumentException exception) {
            throw TicketStoreFailure.unavailable();
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedTicket(String value, int expiresInSeconds) {}

    public record TicketMetadata(
            UUID actorId, String tokenFingerprint, String origin, Instant issuedAt, Instant expiresAt) {}

    public static final class TicketStoreFailure extends RuntimeException {
        enum Kind {
            INVALID,
            RATE_LIMITED,
            UNAVAILABLE
        }

        private final Kind kind;

        private TicketStoreFailure(Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }

        static TicketStoreFailure invalid() {
            return new TicketStoreFailure(Kind.INVALID);
        }

        static TicketStoreFailure rateLimited() {
            return new TicketStoreFailure(Kind.RATE_LIMITED);
        }

        static TicketStoreFailure unavailable() {
            return new TicketStoreFailure(Kind.UNAVAILABLE);
        }
    }
}

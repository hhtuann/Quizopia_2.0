package com.quizopia.identity.infrastructure.admission;

import com.quizopia.identity.application.admission.UserAuthorityFacts;
import com.quizopia.identity.persistence.entity.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Version 1 is exactly seven pipe-delimited canonical fields; no JSON coercion/defaults. */
@Component
public final class UserAuthorityCodec {
    public String encode(UserAuthorityFacts facts) {
        return facts.schemaVersion() + "|" + facts.userId() + "|" + (facts.exists() ? "PRESENT" : "ABSENT")
                + "|"
                + (facts.accountStatus() == null
                        ? "NULL"
                        : "VALUE:"
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(facts.accountStatus().getBytes(StandardCharsets.UTF_8)))
                + "|" + (facts.emailVerified() ? "VERIFIED" : "UNVERIFIED")
                + "|"
                + (facts.roles().isEmpty()
                        ? "NONE"
                        : facts.roles().stream().map(Enum::name).collect(Collectors.joining(",")))
                + "|"
                + facts.revokedBefore()
                        .map(time -> "AT:" + time.getEpochSecond() + ":" + time.getNano())
                        .orElse("NONE");
    }

    public UserAuthorityFacts decode(String value) {
        if (value == null || value.length() > 1024) throw invalid();
        try {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 7 || !fields[0].equals("1")) throw invalid();
            boolean exists =
                    switch (fields[2]) {
                        case "PRESENT" -> true;
                        case "ABSENT" -> false;
                        default -> throw invalid();
                    };
            String status;
            if (fields[3].equals("NULL")) status = null;
            else if (fields[3].startsWith("VALUE:"))
                status = new String(Base64.getUrlDecoder().decode(fields[3].substring(6)), StandardCharsets.UTF_8);
            else throw invalid();
            boolean verified =
                    switch (fields[4]) {
                        case "VERIFIED" -> true;
                        case "UNVERIFIED" -> false;
                        default -> throw invalid();
                    };
            List<UserRole> roles = fields[5].equals("NONE")
                    ? List.of()
                    : Arrays.stream(fields[5].split(",", -1))
                            .map(UserRole::valueOf)
                            .toList();
            Optional<Instant> cutoff;
            if (fields[6].equals("NONE")) cutoff = Optional.empty();
            else {
                String[] parts = fields[6].split(":", -1);
                if (parts.length != 3 || !parts[0].equals("AT")) throw invalid();
                int nanos = Integer.parseInt(parts[2]);
                if (nanos < 0 || nanos > 999999999) throw invalid();
                cutoff = Optional.of(Instant.ofEpochSecond(Long.parseLong(parts[1]), nanos));
            }
            UserAuthorityFacts facts =
                    new UserAuthorityFacts(1, UUID.fromString(fields[1]), exists, status, verified, roles, cutoff);
            if (!encode(facts).equals(value)) throw invalid();
            return facts;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid authority record");
    }
}

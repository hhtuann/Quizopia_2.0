package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.identity.application.admission.UserAuthorityFacts;
import com.quizopia.identity.infrastructure.admission.UserAuthorityCodec;
import com.quizopia.identity.persistence.entity.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class UserAuthorityFactsTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UserAuthorityCodec codec = new UserAuthorityCodec();

    private UserAuthorityFacts facts(Optional<Instant> cutoff) {
        return new UserAuthorityFacts(
                1, ACTOR, true, "ACTIVE", true, List.of(UserRole.TEACHER, UserRole.STUDENT, UserRole.ADMIN), cutoff);
    }

    @Test
    void canonicalRolesAndSerializationAreFrozen() {
        var facts = facts(Optional.empty());
        assertEquals(List.of(UserRole.ADMIN, UserRole.STUDENT, UserRole.TEACHER), facts.roles());
        assertEquals(
                "1|00000000-0000-0000-0000-000000000001|PRESENT|VALUE:QUNUSVZF|VERIFIED|ADMIN,STUDENT,TEACHER|NONE",
                codec.encode(facts));
        assertEquals(facts, codec.decode(codec.encode(facts)));
        assertThrows(UnsupportedOperationException.class, () -> facts.roles().clear());
    }

    @Test
    void preservesCutoffNanosecondsAndInclusiveSemantics() {
        var time = Instant.parse("2026-10-11T01:02:03.123456Z");
        var facts = facts(Optional.of(time));
        assertEquals(facts, codec.decode(codec.encode(facts)));
        assertTrue(facts.revokes(time));
        assertTrue(facts.revokes(time.minusNanos(1)));
        assertFalse(facts.revokes(time.plusNanos(1)));
        assertNotEquals(facts, facts(Optional.of(time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))));
    }

    @Test
    void explicitNoneAndSqlNullAreDifferentFromMissingFields() {
        var nullable = new UserAuthorityFacts(1, ACTOR, true, null, false, List.of(), Optional.empty());
        assertEquals(nullable, codec.decode(codec.encode(nullable)));
        assertFalse(nullable.eligible());
        var absent = new UserAuthorityFacts(1, ACTOR, false, null, false, List.of(), Optional.empty());
        assertNotEquals(nullable, absent);
        assertEquals(absent, codec.decode(codec.encode(absent)));
    }

    @Test
    void typedEqualityIncludesEveryAuthorityFact() {
        var base = new UserAuthorityFacts(1, ACTOR, true, "ACTIVE", true, List.of(UserRole.STUDENT), Optional.empty());
        assertTrue(base.eligible());
        assertNotEquals(
                base,
                new UserAuthorityFacts(1, UUID.randomUUID(), true, "ACTIVE", true, base.roles(), Optional.empty()));
        assertNotEquals(
                base,
                new UserAuthorityFacts(
                        1, ACTOR, true, "PENDING_EMAIL_VERIFICATION", true, base.roles(), Optional.empty()));
        assertNotEquals(base, new UserAuthorityFacts(1, ACTOR, true, "ACTIVE", false, base.roles(), Optional.empty()));
        assertNotEquals(base, facts(Optional.empty()));
        assertNotEquals(
                base, new UserAuthorityFacts(1, ACTOR, true, "ACTIVE", true, base.roles(), Optional.of(Instant.EPOCH)));
    }

    @Test
    void rejectsUnsupportedVersionsAndInconsistentAbsentFacts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UserAuthorityFacts(2, ACTOR, false, null, false, List.of(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UserAuthorityFacts(1, ACTOR, false, "ACTIVE", true, List.of(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UserAuthorityFacts(
                        1, ACTOR, true, "ACTIVE", true, List.of(UserRole.STUDENT, UserRole.STUDENT), Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void rejectsMalformedOrNoncanonicalRecords(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> codec.decode(value));
        assertEquals("Invalid authority record", error.getMessage());
        assertNull(error.getCause());
    }

    static Stream<String> malformed() {
        String base =
                "1|00000000-0000-0000-0000-000000000001|PRESENT|VALUE:QUNUSVZF|VERIFIED|ADMIN,STUDENT,TEACHER|NONE";
        return Stream.of(
                null,
                "",
                "{}",
                base.replaceFirst("1", "2"),
                base + "|extra",
                base.replace("|NONE", ""),
                base.replace("NONE", "null"),
                base.replace("NONE", ""),
                base.replace("VERIFIED", "null"),
                base.replace("PRESENT", "true"),
                base.replace("VALUE:QUNUSVZF", ""),
                base.replace("VALUE:QUNUSVZF", "null"),
                base.replace("QUNUSVZF", "QUNUSVZF="),
                base.replace("ADMIN,STUDENT,TEACHER", "TEACHER,STUDENT,ADMIN"),
                base.replace("ADMIN,STUDENT,TEACHER", "STUDENT,STUDENT"),
                base.replace("ADMIN", "UNKNOWN"),
                base.replace("NONE", "AT:1:1000000000"),
                base.replace("NONE", "AT:1:-1"),
                base.replace("NONE", "AT:1:000000001"),
                base.replace("NONE", "AT:1"),
                base.replace("00000000-0000-0000-0000-000000000001", "0-0-0-0-1"));
    }
}

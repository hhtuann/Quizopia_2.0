package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.audit.*;
import com.quizopia.assessment.persistence.TeacherAssessmentQueries;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerAuditTest {
    @Test
    void canonicalTransitionBytesAreStableAcrossFreshJvms() throws Exception {
        var javaBinary = java.nio.file.Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String expected = null;
        for (int i = 0; i < 6; i++) {
            var process = new ProcessBuilder(javaBinary, "-cp", classpath, CanonicalProbe.class.getName())
                    .redirectErrorStream(true)
                    .start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Canonical JVM probe timed out");
                assertEquals(0, process.exitValue(), "Canonical JVM probe failed");
                String bytes = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (expected == null) expected = bytes;
                else assertEquals(expected, bytes, "Canonical bytes changed across JVMs");
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }

    public static final class CanonicalProbe {
        public static void main(String[] args) {
            var test = new AnswerAuditTest();
            var event =
                    test.parse(test.body(",\"transition\":{\"fromOptionLabels\":[\"A\"],\"toOptionLabels\":[\"C\"]}"));
            System.out.print(Base64.getEncoder().encodeToString(event.canonical()));
        }
    }

    String body(String extra) {
        return "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-4000-8000-000000000001\",\"streamId\":\"00000000-0000-4000-8000-000000000002\",\"sequence\":1,\"type\":\"ANSWER_CHANGED\",\"questionNumber\":1"
                + extra + "}";
    }

    AnswerObservation parse(String body) {
        return AnswerObservation.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    tools.jackson.databind.JsonNode question(String type) {
        return SnapshotFixtures.JSON.readTree("{\"type\":\"" + type
                + "\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"},{\"label\":\"D\"}]}");
    }

    @Test
    void snapshotBoundDiscreteTransitionsAreObservationsNotSavedAnswers() {
        var observation = parse(body(",\"transition\":{\"fromOptionLabels\":[\"A\"],\"toOptionLabels\":[\"C\"]}"));
        observation.validate(question("SINGLE_CHOICE"));
        observation.validate(question("MULTIPLE_CHOICE"));
        assertEquals(List.of("A"), observation.fromOptionLabels());
        assertEquals(List.of("C"), observation.toOptionLabels());
        assertFalse(observation.toString().contains("OptionLabels"));
        assertThrows(PublicationFailure.class, () -> observation.validate(question("NUMERIC_FILL")));
        assertThrows(PublicationFailure.class, () -> observation.validate(question("TRUE_FALSE_MATRIX")));
        var metadata = parse(body(""));
        metadata.validate(question("TRUE_FALSE_MATRIX"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ",\"source\":\"SERVER_AUTHORITY\"",
                ",\"studentId\":\"forged\"",
                ",\"correctAnswer\":\"A\"",
                ",\"value\":\"0001\"",
                ",\"schemaVersion\":1",
                ",\"transition\":{\"fromOptionLabels\":[\"A\"],\"toOptionLabels\":[\"C\"],\"correct\":true}",
                ",\"transition\":{\"fromOptionLabels\":[\"A\",\"A\"],\"toOptionLabels\":[\"C\"]}",
                ",\"transition\":{\"fromOptionLabels\":[\"Z\"],\"toOptionLabels\":[\"C\"]}",
                ",\"transition\":{\"fromOptionLabels\":[\"C\",\"A\"],\"toOptionLabels\":[\"B\"]}",
                ",\"observedAt\":123",
                ",\"transition\":null"
            })
    void arbitraryActorKeyNumericDuplicateAndUnboundedDetailCannotEnter(String extra) {
        assertThrows(PublicationFailure.class, () -> parse(body(extra)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1e1", "true", "\"1\"", "-1", "9223372036854775808"})
    void clientSequenceIsStrictIntegralSigned64(String sequence) {
        assertThrows(
                PublicationFailure.class, () -> parse(body("").replace("\"sequence\":1", "\"sequence\":" + sequence)));
    }

    @Test
    void noopsCategoryMismatchNumericCaptureAndOversizeAreDenied() {
        for (String transition : List.of(
                "{\"fromOptionLabels\":[\"A\"],\"toOptionLabels\":[\"A\"]}",
                "{\"fromOptionLabels\":[],\"toOptionLabels\":[\"A\"]}"))
            assertThrows(PublicationFailure.class, () -> parse(body(",\"transition\":" + transition))
                    .validate(question("SINGLE_CHOICE")));
        assertThrows(PublicationFailure.class, () -> parse("x".repeat(4097)));
        assertThrows(PublicationFailure.class, () -> parse(body("")).validate(question("NUMERIC_FILL")));
        assertThrows(
                PublicationFailure.class,
                () -> parse(body("").replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297")));
    }

    @Test
    void aesGcmProtectsChoicesAndReplayContextWithoutPlaintextDigests() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var crypto = new AuditEncryption.AesGcm("fixture", key);
        byte[] secret = body(",\"transition\":{\"fromOptionLabels\":[\"A\"],\"toOptionLabels\":[\"C\"]}")
                .getBytes(StandardCharsets.UTF_8);
        var sealed = crypto.encrypt("snapshot-attempt-event-stream-sequence", secret);
        assertArrayEquals(
                secret, crypto.decrypt(sealed.keyId(), "snapshot-attempt-event-stream-sequence", sealed.bytes()));
        assertFalse(Arrays.equals(
                sealed.bytes(),
                crypto.encrypt("snapshot-attempt-event-stream-sequence", secret).bytes()));
        assertThrows(
                PublicationFailure.class, () -> crypto.decrypt(sealed.keyId(), "foreign-snapshot", sealed.bytes()));
        var tampered = sealed.bytes();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(
                PublicationFailure.class,
                () -> crypto.decrypt(sealed.keyId(), "snapshot-attempt-event-stream-sequence", tampered));
        assertThrows(
                PublicationFailure.class,
                () -> crypto.decrypt("foreign-key", "snapshot-attempt-event-stream-sequence", sealed.bytes()));
        assertFalse(sealed.toString().contains("ANSWER_CHANGED"));
        assertFalse(crypto.toString().contains(Base64.getEncoder().encodeToString(key)));
        assertThrows(PublicationFailure.class, () -> new AuditEncryption.Pending().encrypt("any", secret));
        assertThrows(PublicationFailure.class, () -> new SensitiveAssessmentPolicy.Pending()
                .expiresAt(java.time.Instant.now()));
    }

    @Test
    void upperInclusiveBoundaryFormulaIsExactForAllEdgesAndNearBoundaryRounding() {
        for (int i = 0; i <= 20; i++)
            assertEquals(
                    Math.max(0, i - 1), TeacherAssessmentQueries.bin(BigDecimal.valueOf(i), BigDecimal.valueOf(20)));
        assertEquals(0, TeacherAssessmentQueries.bin(new BigDecimal("199"), new BigDecimal("4000")));
        assertEquals(1, TeacherAssessmentQueries.bin(new BigDecimal("201"), new BigDecimal("4000")));
        assertEquals(6, TeacherAssessmentQueries.bin(BigDecimal.ONE, BigDecimal.valueOf(3)));
        assertThrows(PublicationFailure.class, () -> TeacherAssessmentQueries.bin(BigDecimal.ONE, BigDecimal.ZERO));
        assertThrows(PublicationFailure.class, () -> TeacherAssessmentQueries.bin(BigDecimal.TEN, BigDecimal.ONE));
    }
}

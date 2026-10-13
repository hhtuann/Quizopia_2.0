package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.api.StudentAttemptController;
import com.quizopia.assessment.application.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.oauth2.jwt.Jwt;

class StudentAttemptHttpTest {
    StudentAttemptService service = mock(StudentAttemptService.class);
    StudentAttemptController controller = new StudentAttemptController(service);
    String id = UUID.randomUUID().toString();
    Jwt user = Jwt.withTokenValue("evidence")
            .header("alg", "RS256")
            .subject(UUID.randomUUID().toString())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

    MockHttpServletRequest request(String body) {
        var r = new MockHttpServletRequest("POST", "/api/assessments/attempts/" + id + "/submit");
        r.addHeader("Content-Type", "application/json");
        r.addHeader("Idempotency-Key", "unique-request-key-01");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    void invalid(MockHttpServletRequest r) {
        var f = assertThrows(
                PublicationFailure.class, () -> controller.submit(user, id, r, new MockHttpServletResponse()));
        assertEquals("INVALID_REQUEST", f.code());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "[]",
                "null",
                "{}",
                "{\"expectedRevision\":0}",
                "{\"expectedRevision\":\"00\"}",
                "{\"expectedRevision\":\"-1\"}",
                "{\"expectedRevision\":\"9223372036854775808\"}",
                "{\"expectedRevision\":\"0\",\"studentId\":\"forged\"}",
                "{\"expectedRevision\":\"0\",\"expectedRevision\":\"1\"}",
                "{\"expectedRevision\":\"0\"}{}",
                "{\"expectedRevision\":\"0\",\"answers\":null}",
                "{\"expectedRevision\":\"0\",\"answers\":[]}"
            })
    void malformedBodyCannotReachApplication(String raw) {
        invalid(request(raw));
    }

    @Test
    void nestedBodyAndLongNumbersAreResourceBounded() {
        invalid(request("{\"expectedRevision\":\"0\",\"answers\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}"));
    }

    @Test
    void duplicateKeyHeaderQueryAndEncodingFailBeforeApplication() {
        var r = request("{\"expectedRevision\":\"0\"}");
        r.addHeader("Idempotency-Key", "another-valid-key");
        invalid(r);
        r = request("{\"expectedRevision\":\"0\"}");
        r.setQueryString("x=1&x=2");
        invalid(r);
        r = request("{\"expectedRevision\":\"0\"}");
        r.addHeader("Content-Encoding", "gzip");
        invalid(r);
        r = request("{\"expectedRevision\":\"0\"}");
        r.removeHeader("Content-Type");
        r.addHeader("Content-Type", "text/plain");
        invalid(r);
    }

    @Test
    void exactBodyBudgetAcceptedOversizedMutationFree() {
        String prefix = "{\"expectedRevision\":\"0\"}";
        var exact = request(prefix + " ".repeat(StudentAttemptController.BODY_LIMIT - prefix.length()));
        controller.submit(user, id, exact, new MockHttpServletResponse());
        verify(service)
                .submit(any(), eq(UUID.fromString(id)), eq(0L), matches("[0-9a-f]{64}"), isNull(), eq("evidence"));
        reset(service);
        invalid(request(prefix + " ".repeat(StudentAttemptController.BODY_LIMIT - prefix.length() + 1)));
    }

    @Test
    void getRejectsBodyAndQueryBeforeRead() {
        var r = request("{}");
        assertThrows(PublicationFailure.class, () -> controller.resume(user, id, r, new MockHttpServletResponse()));
        verifyNoInteractions(service);
    }

    @Test
    void exactQuestionAndUuidSpellingRequired() {
        var raw = "{\"expectedRevision\":\"0\",\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":null}}";
        for (String q : List.of("0", "01", "-1", "2147483648"))
            assertThrows(
                    PublicationFailure.class,
                    () -> controller.save(user, id, q, request(raw), new MockHttpServletResponse()));
        assertThrows(
                PublicationFailure.class,
                () -> controller.submit(
                        user,
                        id.toUpperCase(Locale.ROOT),
                        request("{\"expectedRevision\":\"0\"}"),
                        new MockHttpServletResponse()));
        verifyNoInteractions(service);
    }

    @Test
    void startAllowsOnlyOptionalTextLocator() {
        for (String raw : List.of("{\"locator\":null}", "{\"locator\":12}", "{\"studentId\":\"x\"}"))
            assertThrows(
                    PublicationFailure.class,
                    () -> controller.start(user, id, request(raw), new MockHttpServletResponse()));
        verifyNoInteractions(service);
    }
}

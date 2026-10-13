package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.infrastructure.SnapshotValidator;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.node.*;

/** Capacity compatibility of the real migrated C1 path with production JDBC limits. */
@Testcontainers
class StudentCapacityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    @ParameterizedTest
    @ValueSource(ints = {1024, 12613})
    void acceptedSnapshotCanFinalize(int count) {
        var fixture = new AtomicFinalizationIntegrationTest();
        fixture.setup();
        final int[] bytes = {0};
        var p = fixture.publication(0, root -> {
            var questions = (ArrayNode) root.path("structuredContent").path("questions");
            var numeric = (ObjectNode) questions.get(3).deepCopy();
            String stem = count == 1024 ? "Đề bài 😀" : "x";
            numeric.put("stemMarkdown", stem);
            numeric.putNull("explanationMarkdown");
            questions.removeAll();
            var source = new StringBuilder();
            for (int i = 1; i <= count; i++) {
                var q = numeric.deepCopy();
                q.put("number", i);
                questions.add(q);
                source.append("Câu ")
                        .append(i)
                        .append(" [NUMERIC_FILL]: ")
                        .append(stem)
                        .append("\nĐáp án: 0001\n");
            }
            root.put("sourceSnapshot", source.toString());
            bytes[0] = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        });
        assertTrue(bytes[0] <= 2097152);
        String protectedSnapshot = fixture.scalar(
                "SELECT payload::text FROM publication_snapshots WHERE publication_id=?", p.publication());
        new SnapshotValidator()
                .validate(protectedSnapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8), p.configuration());
        var entries = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= count; i++)
            entries.add(Map.of("questionNumber", i, "answer", Map.of("type", "NUMERIC_FILL", "value", "0001")));
        String answers = SnapshotFixtures.JSON.writeValueAsString(entries);
        // Mirror application.yml's actual production JDBC socket/login limits.
        var properties = new Properties();
        properties.setProperty("ApplicationName", fixture.schema + "_production_limits");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("loginTimeout", "3");
        fixture.source.setConnectionProperties(properties);
        var store = new com.quizopia.assessment.persistence.JdbcStudentAttemptStore(
                fixture.db,
                fixture.manager,
                com.quizopia.assessment.configuration.AssessmentTransactionProperties.defaults(),
                fixture.core);
        var service = new com.quizopia.assessment.application.StudentAttemptService(
                store,
                org.mockito.Mockito.mock(com.quizopia.assessment.application.PublicationSources.class),
                fixture.core);
        var controller = new com.quizopia.assessment.api.StudentAttemptController(service);
        var user = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("capacity-component-evidence")
                .header("alg", "RS256")
                .subject(fixture.student.toString())
                .expiresAt(fixture.now().plusSeconds(3600))
                .build();
        String locator = fixture.scalar("SELECT private_locator FROM publications WHERE id=?", p.publication());
        var startRequest = request("{\"locator\":\"" + locator + "\"}");
        var view = controller.start(
                user,
                p.publication().toString(),
                startRequest,
                new org.springframework.mock.web.MockHttpServletResponse());
        String delivery = SnapshotFixtures.JSON.writeValueAsString(view);
        assertEquals(count, view.questions().size());
        assertEquals(count, view.answers().size());
        for (String secret : List.of("numericAnswer", "explanationMarkdown", "sourceSnapshot", "correctAnswer"))
            assertFalse(delivery.contains(secret));
        String submit = "{\"expectedRevision\":\"0\",\"answers\":" + answers + "}";
        assertTrue(submit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2097152);
        long started = System.nanoTime();
        try {
            var result = controller.submit(
                    user,
                    view.attemptId().toString(),
                    request(submit),
                    new org.springframework.mock.web.MockHttpServletResponse());
            assertEquals(Integer.toString(count), result.earnedWeight());
            assertEquals(
                    Integer.toString(count),
                    fixture.scalar(
                            "SELECT count(*) FROM grading_question_results WHERE result_id=?", result.resultId()));
            assertEquals(
                    "1",
                    fixture.scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", view.attemptId()));
            System.out.println("B2 deliveryBytes=" + delivery.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + " completeRequestBytes=" + submit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        } finally {
            System.out.println("B2 capacity audit questions=" + count + " snapshotBytes=" + bytes[0] + " answerBytes="
                    + answers.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " elapsedMs="
                    + (System.nanoTime() - started) / 1000000);
        }
    }

    private org.springframework.mock.web.MockHttpServletRequest request(String body) {
        var r = new org.springframework.mock.web.MockHttpServletRequest();
        r.addHeader("Content-Type", "application/json");
        r.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return r;
    }
}

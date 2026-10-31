package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.TeacherAssessmentViews;
import com.quizopia.assessment.audit.AnswerAuditStore;
import com.quizopia.assessment.security.admission.TeacherAssessmentRoutes;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TeacherAssessmentContractTest {
    @SuppressWarnings("unchecked")
    Map<String, Object> contract() throws Exception {
        try (var input = Files.newInputStream(Path.of("contracts/teacher-assessments.openapi.yaml"))) {
            return new Yaml().load(input);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedResponsePropertiesAreExactRecordAllowlists() throws Exception {
        var schemas = (Map<String, Map<String, Object>>) ((Map<?, ?>) contract().get("components")).get("schemas");
        var types = Map.ofEntries(
                Map.entry("TeacherScore", TeacherAssessmentViews.Score.class),
                Map.entry("TeacherMean", TeacherAssessmentViews.Mean.class),
                Map.entry("TeacherScoreBin", TeacherAssessmentViews.Bin.class),
                Map.entry("TeacherStudentOverview", TeacherAssessmentViews.Overview.class),
                Map.entry("TeacherAttemptSummary", TeacherAssessmentViews.Attempt.class),
                Map.entry("TeacherStudentSummary", TeacherAssessmentViews.Student.class),
                Map.entry("TeacherStudents", TeacherAssessmentViews.Students.class),
                Map.entry("TeacherStudentHistory", TeacherAssessmentViews.History.class),
                Map.entry("TeacherAttemptReview", TeacherAssessmentViews.Review.class),
                Map.entry("AuditAck", AnswerAuditStore.Ack.class),
                Map.entry("ObservationChoice", AnswerAuditStore.ObservationChoice.class),
                Map.entry("AuditCoverage", AnswerAuditStore.Coverage.class),
                Map.entry("AuditItem", AnswerAuditStore.Item.class),
                Map.entry("AuditTimeline", AnswerAuditStore.Timeline.class));
        for (var entry : types.entrySet()) {
            var schema = schemas.get(entry.getKey());
            assertNotNull(schema);
            assertEquals(false, schema.get("additionalProperties"));
            var names = Arrays.stream(entry.getValue().getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toSet());
            assertEquals(names, ((Map<?, ?>) schema.get("properties")).keySet(), entry.getKey());
            assertEquals(names, new HashSet<>((List<String>) schema.get("required")), entry.getKey());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void contractPathsMatchExactIndependentServiceRouteInventory() throws Exception {
        var paths = (Map<String, Map<String, Object>>) contract().get("paths");
        assertEquals(7, paths.size());
        for (var entry : paths.entrySet()) {
            String actual = entry.getKey()
                    .replace("{publicationId}", AdmissionTestTokens.ACTOR)
                    .replace("{studentId}", AdmissionTestTokens.ACTOR)
                    .replace("{attemptId}", AdmissionTestTokens.ACTOR);
            for (String method : List.of("get", "post", "put"))
                if (entry.getValue().containsKey(method)) {
                    assertEquals(
                            method.equals("post") || actual.endsWith("/answer-observation-choice")
                                    ? "STUDENT"
                                    : "TEACHER",
                            TeacherAssessmentRoutes.role(method.toUpperCase(Locale.ROOT), actual));
                    assertNull(TeacherAssessmentRoutes.role("DELETE", actual));
                    assertNull(TeacherAssessmentRoutes.role(method.toUpperCase(Locale.ROOT), actual + "/extra"));
                }
        }
    }
}

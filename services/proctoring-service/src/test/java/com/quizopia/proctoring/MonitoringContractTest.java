package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.api.MonitoringController;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring;
import com.quizopia.proctoring.security.admission.ProctoringRoutes;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

/** Reflection against implemented adapters/records prevents a second contradictory wire model. */
class MonitoringContractTest {
    @SuppressWarnings("unchecked")
    Map<String, Object> doc() throws Exception {
        try (var in = Files.newInputStream(Path.of("contracts/monitoring.openapi.yaml"))) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void exactFiveControllerMethodsEqualPublishedOpenApiAndProtectedInventory() throws Exception {
        var doc = doc();
        assertEquals("3.1.0", doc.get("openapi"));
        Set<String> contract = new HashSet<>();
        map(doc.get("paths")).forEach((path, methods) -> map(methods).keySet().forEach(method -> {
            contract.add(method.toUpperCase(Locale.ROOT) + " " + path);
            assertNotNull(ProctoringRoutes.role(
                    method.toUpperCase(Locale.ROOT), path.replace("{id}", "00000000-0000-4000-8000-000000000001")));
        }));
        String base =
                MonitoringController.class.getAnnotation(RequestMapping.class).value()[0];
        Set<String> actual = new HashSet<>();
        for (var method : MonitoringController.class.getDeclaredMethods()) {
            var get = method.getAnnotation(GetMapping.class);
            var post = method.getAnnotation(PostMapping.class);
            if (get != null) actual.add("GET " + base + (get.value().length == 0 ? "" : get.value()[0]));
            if (post != null) actual.add("POST " + base + (post.value().length == 0 ? "" : post.value()[0]));
        }
        assertEquals(5, actual.size());
        assertEquals(actual, contract);
        assertNull(ProctoringRoutes.role(
                "POST", "/internal/proctoring/attempts/00000000-0000-4000-8000-000000000001/authority"));
        assertFalse(new MonitoringProperties().isEnabled());
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            Map<String, Object> config = new Yaml().load(in);
            assertEquals(
                    "${PROCTORING_ENABLED:false}",
                    map(config.get("quizopia.proctoring.monitoring")).get("enabled"));
        }
    }

    @Test
    void dtoFieldsAndEvidenceVersionsMatchSourceRecordsWithoutSensitiveFields() throws Exception {
        var schemas = map(map(doc().get("components")).get("schemas"));
        Map<String, Class<?>> records = Map.of(
                "Create",
                MonitoringController.Create.class,
                "Event",
                Monitoring.Event.class,
                "Session",
                Monitoring.Session.class,
                "Acknowledgment",
                Monitoring.Acknowledgment.class,
                "Evidence",
                Monitoring.Evidence.class,
                "Page",
                Monitoring.Page.class);
        records.forEach((name, type) -> {
            var actual = Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toSet());
            var schema = map(schemas.get(name));
            assertEquals(actual, map(schema.get("properties")).keySet(), name);
            assertEquals(Boolean.FALSE, schema.get("additionalProperties"), name);
        });
        var event = map(schemas.get("Event"));
        assertTrue(((List<?>) event.get("required"))
                .containsAll(List.of("eventId", "streamId", "sequence", "type", "schemaVersion")));
        assertEquals(1, map(map(event.get("properties")).get("schemaVersion")).get("const"));
        Set<String> browserTypes = Arrays.stream(Monitoring.EventType.values())
                .filter(t -> !t.serverOnly())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(browserTypes, new HashSet<>((List<?>)
                map(map(event.get("properties")).get("type")).get("enum")));
        Set<String> prohibited = Set.of(
                "answer", "answers", "url", "email", "password", "token", "clipboard", "keystrokes", "recording");
        for (String name : records.keySet())
            assertTrue(Collections.disjoint(
                    prohibited, map(map(schemas.get(name)).get("properties")).keySet()));
    }
}

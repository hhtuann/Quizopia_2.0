package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.media.*;
import com.quizopia.proctoring.security.admission.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

class MediaContractTest {
    @SuppressWarnings("unchecked")
    Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    Map<String, Object> doc() throws Exception {
        try (var in = Files.newInputStream(Path.of("contracts/media.openapi.yaml"))) {
            return new Yaml().load(in);
        }
    }

    @Test
    void routesAndResponseRecordsMatchPublishedContract() throws Exception {
        var doc = doc();
        assertEquals("3.1.0", doc.get("openapi"));
        Set<String> published = new HashSet<>();
        map(doc.get("paths")).forEach((path, ops) -> map(ops).keySet().stream()
                .filter(m -> Set.of("get", "post").contains(m))
                .forEach(m -> {
                    published.add(m.toUpperCase(Locale.ROOT) + " " + path);
                    assertTrue(MediaRoutes.matches(
                            m.toUpperCase(Locale.ROOT),
                            path.replace("{id}", "00000000-0000-4000-8000-000000000001")
                                    .replace("{grantId}", "00000000-0000-4000-8000-000000000002")));
                    assertNull(map(map(ops).get(m)).get("requestBody"));
                }));
        Set<String> implemented = new HashSet<>();
        String base = MediaController.class.getAnnotation(RequestMapping.class).value()[0];
        for (var method : MediaController.class.getDeclaredMethods()) {
            var get = method.getAnnotation(GetMapping.class);
            var post = method.getAnnotation(PostMapping.class);
            if (get != null) implemented.add("GET " + base + (get.value().length == 0 ? "" : get.value()[0]));
            if (post != null) implemented.add("POST " + base + (post.value().length == 0 ? "" : post.value()[0]));
        }
        assertEquals(4, implemented.size());
        assertEquals(implemented, published);
        var schemas = map(map(doc.get("components")).get("schemas"));
        for (var entry : Map.of("Grant", MediaService.Grant.class, "Status", MediaService.Status.class)
                .entrySet()) {
            var schema = map(schemas.get(entry.getKey()));
            assertEquals(
                    Arrays.stream(entry.getValue().getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .collect(Collectors.toSet()),
                    map(schema.get("properties")).keySet());
            assertEquals(false, schema.get("additionalProperties"));
        }
    }

    @Test
    void defaultsAndProductionBoundaryCannotEnableUnqualifiedMedia() {
        var p = new MediaProperties();
        assertFalse(p.isEnabled());
        assertFalse(p.isWorkerEnabled());
        assertFalse(p.qualified());
        p.setEnabled(true);
        assertThrows(IllegalStateException.class, () -> p.validate(false));
        p.setApiUrl(URI.create("http://127.0.0.1:7880"));
        p.setPublicUrl(URI.create("ws://127.0.0.1:7880"));
        p.setApiKey("synthetic-fixture");
        p.setApiSecret("synthetic-fixture-secret-at-least-32-bytes");
        assertThrows(IllegalStateException.class, () -> p.validate(false));
        p.setAllowInsecureLoopback(true);
        p.validate(false);
        p.setRevocationMode(MediaProperties.RevocationMode.CLOUD_STRICT);
        p.setQualificationReference("synthetic-local-attestation-not-provider-approval");
        assertTrue(p.qualified());
        assertThrows(IllegalStateException.class, () -> p.validate(true));
        p.setLeaseDuration(Duration.ofSeconds(31));
        assertThrows(IllegalStateException.class, () -> p.validate(false));
        assertFalse(p.toString().contains(p.getApiSecret()));
    }
}

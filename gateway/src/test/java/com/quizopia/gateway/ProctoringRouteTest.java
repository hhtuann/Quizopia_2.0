package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.gateway.security.admission.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProctoringRouteTest {
    private final String base = "/api/proctoring/monitoring/sessions";
    private final String id = "12345678-1234-4234-8234-123456789abc";

    @Test
    void onlyFiveExactAdaptersOpenWithExpectedRoles() {
        assertEquals("STUDENT", AdmissionRoutes.requiredRole("POST", base));
        assertEquals("STUDENT", AdmissionRoutes.requiredRole("GET", base + "/" + id));
        assertEquals("STUDENT", AdmissionRoutes.requiredRole("POST", base + "/" + id + "/events"));
        assertEquals("STUDENT", AdmissionRoutes.requiredRole("POST", base + "/" + id + "/reconcile"));
        assertEquals("TEACHER", AdmissionRoutes.requiredRole("GET", base + "/" + id + "/events"));
        for (String path : List.of(
                base + "/",
                base + "/" + id + "/events/",
                base + "/" + id.toUpperCase(Locale.ROOT),
                base + "/" + id + ";x=1",
                base + "/../sessions",
                base + "/%31" + id,
                base + "//" + id,
                "/api/proctoring/token",
                "/internal/proctoring/attempts/" + id + "/authority")) {
            assertNull(AdmissionRoutes.requiredRole("GET", path), path);
            assertTrue(AdmissionRoutes.closed(path), path);
        }
        for (String method : List.of("PUT", "DELETE", "PATCH")) assertNull(AdmissionRoutes.requiredRole(method, base));
    }
}

package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.gateway.security.admission.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClosureRouteTest {
    @Test
    void exactEightOperationsRolesAndIndependentFlags() {
        String id = "00000000-0000-0000-0000-000000000001",
                p = "/api/assessments/publications/" + id,
                c = p + "/closure-requests/" + id,
                n = "/api/assessments/notifications";
        var operations = List.of(
                new String[] {"GET", p + "/results", "TEACHER"},
                new String[] {"POST", p + "/close", "TEACHER"},
                new String[] {"POST", p + "/closure-requests", "TEACHER"},
                new String[] {"GET", c, "TEACHER"},
                new String[] {"POST", c + "/cancel", "TEACHER"},
                new String[] {"POST", c + "/complete", "TEACHER"},
                new String[] {"GET", n, "STUDENT"},
                new String[] {"GET", n + "/" + id, "STUDENT"});
        for (var op : operations) {
            assertEquals(op[2], PublicationRoutes.requiredRole(op[0], op[1]));
            assertTrue(ClosureRoutes.enabled(op[0], op[1], true, true, true));
            assertFalse(ClosureRoutes.enabled(op[0], op[1], false, false, false));
            assertNull(ClosureRoutes.role("DELETE", op[1]));
            assertNull(ClosureRoutes.role(op[0], op[1] + "/"));
            assertNull(ClosureRoutes.role(op[0], op[1] + "/extra"));
        }
        assertTrue(ClosureRoutes.enabled("GET", n, false, false, true));
        assertFalse(ClosureRoutes.enabled("GET", n, true, true, false));
        assertTrue(ClosureRoutes.enabled("GET", p + "/results", true, false, false));
        assertTrue(ClosureRoutes.enabled("POST", p + "/close", false, true, false));
        assertNull(
                PublicationRoutes.requiredRole("POST", "/internal/security/notice-recipients/" + id + "/eligibility"));
    }
}

package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.security.admission.PublicationRoutes;
import com.quizopia.assessment.security.admission.StudentAttemptRoutes;
import org.junit.jupiter.api.Test;

class StudentAttemptRouteTest {
    @Test
    void exactInventoryAndFlagMatrix() {
        String id = "00000000-0000-0000-0000-000000000001",
                a = "/api/assessments/attempts/" + id,
                p = "/api/assessments/publications/" + id;
        var operations = java.util.List.of(
                new String[] {"POST", p + "/attempts/start"},
                new String[] {"POST", p + "/attempts/retake"},
                new String[] {"GET", a},
                new String[] {"POST", a + "/questions/1/answer"},
                new String[] {"POST", a + "/submit"},
                new String[] {"POST", a + "/abandon"},
                new String[] {"GET", a + "/result"},
                new String[] {"GET", a + "/review"},
                new String[] {"GET", p + "/aggregate-score"});
        for (var op : operations) {
            assertEquals("STUDENT", PublicationRoutes.requiredRole(op[0], op[1]));
            assertTrue(StudentAttemptRoutes.enabled(op[0], op[1], true, true));
            assertFalse(StudentAttemptRoutes.enabled(op[0], op[1], false, false));
            assertFalse(StudentAttemptRoutes.matches("DELETE", op[1]));
            assertFalse(StudentAttemptRoutes.matches(op[0], op[1] + "/"));
            assertFalse(StudentAttemptRoutes.matches(op[0], op[1] + "/extra"));
        }
        assertFalse(StudentAttemptRoutes.enabled("POST", a + "/submit", true, false));
        assertFalse(StudentAttemptRoutes.enabled("POST", a + "/submit", false, true));
        assertTrue(StudentAttemptRoutes.enabled("GET", a + "/result", false, true));
        assertTrue(StudentAttemptRoutes.enabled("GET", a, true, false));
        for (String path : java.util.List.of(
                p + "/closure",
                a + "/questions/0/answer",
                a + "/questions/01/answer",
                "/internal/security/notice-recipients/" + id + "/eligibility"))
            assertNull(PublicationRoutes.requiredRole("POST", path));
    }
}

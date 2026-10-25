package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.gateway.security.admission.AdmissionRoutes;
import org.junit.jupiter.api.Test;

class OwnerDirectoryInventoryTest {
    @Test
    void onlyExactCollectionGetIsAddedWithTeacherAdmission() {
        for (String path : java.util.List.of("/classrooms", "/api/assessments/publications")) {
            assertEquals("TEACHER", AdmissionRoutes.requiredRole("GET", path));
            for (String method : java.util.List.of("HEAD", "PUT", "DELETE", "PATCH")) {
                assertNull(AdmissionRoutes.requiredRole(method, path));
            }
            for (String alias : java.util.List.of(path + "/", path + "/unknown", path + "/search")) {
                assertNull(AdmissionRoutes.requiredRole("GET", alias));
                assertTrue(AdmissionRoutes.closed(alias));
            }
        }
    }
}

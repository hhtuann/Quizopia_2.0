package com.quizopia.gateway.security.admission;

/** Exact Task 2 surface; kept in parity with Assessment's independent inventory. */
public final class TeacherAssessmentRoutes {
    private TeacherAssessmentRoutes() {}

    public static String role(String method, String path) {
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String p = "/api/assessments/publications/" + id;
        if (method.equals("GET")
                && (path.matches(p + "/(student-overview|students)")
                        || path.matches(p + "/students/" + id + "/attempts")
                        || path.matches(p + "/attempts/" + id + "/(teacher-review|answer-audit)"))) return "TEACHER";
        if (method.equals("POST") && path.matches(p + "/attempts/" + id + "/answer-observations")) return "STUDENT";
        return null;
    }

    public static boolean paged(String path) {
        return path.endsWith("/students")
                || path.matches(".*/students/[^/]+/attempts")
                || path.endsWith("/answer-audit");
    }
}

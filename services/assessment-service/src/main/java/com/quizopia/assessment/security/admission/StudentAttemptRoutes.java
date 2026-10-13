package com.quizopia.assessment.security.admission;
/** Exact implemented B2 inventory; flags never authorize future closure or inbox paths. */
public final class StudentAttemptRoutes {
    private StudentAttemptRoutes() {}

    public static boolean matches(String method, String path) {
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String p = "/api/assessments/publications/" + id;
        String a = "/api/assessments/attempts/" + id;
        return method.equals("POST")
                        && (path.matches(p + "/attempts/(start|retake)")
                                || path.matches(a + "/(submit|abandon)")
                                || path.matches(a + "/questions/[1-9][0-9]{0,9}/answer"))
                || method.equals("GET")
                        && (path.matches(a + "(?:/(result|review))?") || path.matches(p + "/aggregate-score"));
    }

    public static boolean enabled(String method, String path, boolean attempts, boolean results) {
        if (!matches(method, path)) return false;
        if (path.endsWith("/submit")) return attempts && results;
        if (path.endsWith("/result") || path.endsWith("/review") || path.endsWith("/aggregate-score")) return results;
        return attempts;
    }
}

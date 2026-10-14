package com.quizopia.gateway.security.admission;
/** Exact method/path inventory. Prefix routing alone never grants access. */
public final class ProctoringRoutes {
    private static final String BASE = "/api/proctoring/monitoring/sessions";
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private ProctoringRoutes() {}

    public static String role(String method, String path) {
        if (MediaRoutes.matches(method, path)) return "STUDENT";
        if (method.equals("POST") && path.equals(BASE)) return "STUDENT";
        if (method.equals("GET") && path.matches(BASE + "/" + ID)) return "STUDENT";
        if (method.equals("POST") && path.matches(BASE + "/" + ID + "/(events|reconcile)")) return "STUDENT";
        if (method.equals("GET") && path.matches(BASE + "/" + ID + "/events")) return "TEACHER";
        return null;
    }
}

package com.quizopia.gateway.security.admission;
/** Exact B3 inventory. No wildcard authorizes future assessment operations. */
public final class ClosureRoutes {
    private ClosureRoutes() {}

    public static String role(String method, String path) {
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String p = "/api/assessments/publications/" + id;
        String c = p + "/closure-requests/" + id;
        if (method.equals("GET") && (path.matches(p + "/results") || path.matches(c))) return "TEACHER";
        if (method.equals("POST")
                && (path.matches(p + "/(close|closure-requests)") || path.matches(c + "/(cancel|complete)")))
            return "TEACHER";
        if (method.equals("GET") && path.matches("/api/assessments/notifications(?:/" + id + ")?")) return "STUDENT";
        return null;
    }

    public static boolean enabled(String method, String path, boolean results, boolean closure, boolean inbox) {
        if (role(method, path) == null) return false;
        if (path.startsWith("/api/assessments/notifications")) return inbox;
        return path.endsWith("/results") ? results : closure;
    }

    public static boolean paged(String path) {
        return path.endsWith("/results") || path.equals("/api/assessments/notifications");
    }
}

package com.quizopia.gateway.security.admission;
/** Closed inventory of implemented Publication operations. */
public final class PublicationRoutes {
    private PublicationRoutes() {}

    public static String requiredRole(String method, String path) {
        String base = "/api/assessments/publications";
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if (method.equals("POST") && (path.equals(base + "/entry") || path.matches(base + "/" + id + "/entry")))
            return "STUDENT";
        if (method.equals("POST") && (path.equals(base) || path.matches(base + "/" + id + "/open"))) return "TEACHER";
        if ((method.equals("GET") || method.equals("PUT")) && path.matches(base + "/" + id)) return "TEACHER";
        if (method.equals("PATCH") && path.matches(base + "/" + id + "/schedule")) return "TEACHER";
        return StudentAttemptRoutes.matches(method, path) ? "STUDENT" : null;
    }
}

package com.quizopia.gateway.security.admission;

import java.util.regex.Pattern;

/** Exact inventory. No configurable wildcard can open future business routes. */
public final class AdmissionRoutes {
    private static final Pattern OWNED =
            Pattern.compile("/classrooms/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private AdmissionRoutes() {}

    public static String requiredRole(String method, String path) {
        String publicationRole = PublicationRoutes.requiredRole(method, path);
        if (publicationRole != null) return publicationRole;
        if (method.equals("POST") && path.equals("/classrooms")) return "TEACHER";
        if (method.equals("GET") && OWNED.matcher(path).matches()) return "STUDENT";
        return null;
    }

    public static boolean closed(String path) {
        return java.util.List.of(
                        "/classrooms",
                        "/api/classes",
                        "/api/assessments",
                        "/assessments",
                        "/publications",
                        "/attempts",
                        "/api/proctoring",
                        "/proctoring",
                        "/internal",
                        "/oauth2",
                        "/.well-known")
                .stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}

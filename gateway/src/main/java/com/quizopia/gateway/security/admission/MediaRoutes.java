package com.quizopia.gateway.security.admission;
/** Exact USER inventory; current Student/Teacher resource authority is resolved downstream. */
public final class MediaRoutes {
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String BASE = "/api/proctoring/monitoring/sessions/" + ID + "/media-grants";

    private MediaRoutes() {}

    public static boolean matches(String method, String path) {
        return method.equals("POST") && (path.matches(BASE) || path.matches(BASE + "/" + ID + "/(renew|revoke)"))
                || method.equals("GET") && path.matches(BASE + "/" + ID);
    }
}

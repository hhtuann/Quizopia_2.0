package com.quizopia.identity.api.admission;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class AdmissionErrors {
    private AdmissionErrors() {}

    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        // Only fixed internal codes; never include decoder, database or Redis exceptions.
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

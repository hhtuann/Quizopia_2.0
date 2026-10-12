package com.quizopia.quiz.security.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class InternalErrors {
    private InternalErrors() {}

    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

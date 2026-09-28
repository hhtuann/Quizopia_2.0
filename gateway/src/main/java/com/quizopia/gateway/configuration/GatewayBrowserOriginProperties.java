package com.quizopia.gateway.configuration;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.gateway.browser")
public record GatewayBrowserOriginProperties(List<String> allowedOrigins) {
    public GatewayBrowserOriginProperties {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one browser origin is required");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        var distinct = new HashSet<String>();
        for (String origin : allowedOrigins) {
            validate(origin);
            if (!distinct.add(origin)) {
                throw new IllegalArgumentException("Browser origins must be unique");
            }
        }
    }

    private static void validate(String origin) {
        Objects.requireNonNull(origin, "browser origin");
        if (origin.isBlank() || !origin.equals(origin.trim()) || origin.equals("null") || origin.contains("*")) {
            throw new IllegalArgumentException("Browser origins must be explicit non-null origins");
        }

        URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Browser origin is invalid", exception);
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                        || scheme.toLowerCase(Locale.ROOT).equals("https"))
                || parsed.getHost() == null
                || parsed.getUserInfo() != null
                || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty())
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Browser origin must contain only an HTTP(S) origin");
        }
    }
}

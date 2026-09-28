package com.quizopia.identity.configuration;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.identity.security.browser")
public record TrustedBrowserOriginProperties(List<String> trustedOrigins) {
    public TrustedBrowserOriginProperties {
        Objects.requireNonNull(trustedOrigins, "trustedOrigins");
        if (trustedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted browser origin is required");
        }
        trustedOrigins = List.copyOf(trustedOrigins);
        var distinct = new HashSet<String>();
        for (String origin : trustedOrigins) {
            validate(origin);
            if (!distinct.add(origin)) {
                throw new IllegalArgumentException("Trusted browser origins must be unique");
            }
        }
    }

    public boolean trusts(String origin) {
        return origin != null && trustedOrigins.contains(origin);
    }

    private static void validate(String origin) {
        Objects.requireNonNull(origin, "trusted browser origin");
        if (origin.isBlank() || !origin.equals(origin.trim()) || origin.equals("null") || origin.contains("*")) {
            throw new IllegalArgumentException("Trusted browser origins must be explicit non-null origins");
        }
        URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Trusted browser origin is invalid", exception);
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
            throw new IllegalArgumentException("Trusted browser origin must contain only an HTTP(S) origin");
        }
    }
}

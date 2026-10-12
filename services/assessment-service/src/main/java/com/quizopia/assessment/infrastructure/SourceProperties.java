package com.quizopia.assessment.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.publication.sources")
public class SourceProperties {
    private boolean enabled;
    private URI quizBaseUrl;
    private URI classroomBaseUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public URI getQuizBaseUrl() {
        return quizBaseUrl;
    }

    public void setQuizBaseUrl(URI value) {
        quizBaseUrl = value;
    }

    public URI getClassroomBaseUrl() {
        return classroomBaseUrl;
    }

    public void setClassroomBaseUrl(URI value) {
        classroomBaseUrl = value;
    }

    public void validate(boolean allowLoopback) {
        if (!enabled) return;
        for (var uri : new URI[] {quizBaseUrl, classroomBaseUrl}) {
            if (uri == null
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equals(uri.getScheme())
                            || (allowLoopback
                                    && "http".equals(uri.getScheme())
                                    && java.util.Set.of("127.0.0.1", "localhost", "[::1]")
                                            .contains(uri.getHost()))))
                throw new IllegalStateException("Invalid publication source destination");
        }
    }
}

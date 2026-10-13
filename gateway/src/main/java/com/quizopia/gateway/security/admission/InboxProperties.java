package com.quizopia.gateway.security.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.assessment.closure")
public record InboxProperties(boolean inboxEnabled) {}

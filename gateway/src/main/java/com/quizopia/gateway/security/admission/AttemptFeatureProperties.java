package com.quizopia.gateway.security.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.assessment.features")
public record AttemptFeatureProperties(boolean attemptsEnabled, boolean resultsEnabled, boolean closureEnabled) {}

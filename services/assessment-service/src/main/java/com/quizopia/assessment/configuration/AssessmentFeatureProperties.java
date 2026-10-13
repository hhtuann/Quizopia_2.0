package com.quizopia.assessment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment switches only; no future route or deadline bypass is registered by these flags. */
@ConfigurationProperties("quizopia.assessment.features")
public record AssessmentFeatureProperties(boolean attemptsEnabled, boolean resultsEnabled, boolean closureEnabled) {}

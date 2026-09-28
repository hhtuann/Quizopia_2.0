package com.quizopia.identity.application.emailverification.delivery;

@FunctionalInterface
public interface VerificationEmailDelivery {
    void send(VerificationEmailMessage message);
}

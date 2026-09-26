package com.quizopia.identity.application.serviceclient;

public enum ServiceClientGrantType {
    CLIENT_CREDENTIALS("client_credentials");

    private final String value;

    ServiceClientGrantType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

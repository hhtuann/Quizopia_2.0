package com.quizopia.identity.application.serviceclient;

public enum ServiceClientAuthenticationMethod {
    CLIENT_SECRET_BASIC("client_secret_basic");

    private final String value;

    ServiceClientAuthenticationMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

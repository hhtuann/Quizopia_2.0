package com.quizopia.identity.security.token;

public class UserAccessTokenIssuanceException extends RuntimeException {
    public UserAccessTokenIssuanceException() {
        super("User access token cannot be issued");
    }
}

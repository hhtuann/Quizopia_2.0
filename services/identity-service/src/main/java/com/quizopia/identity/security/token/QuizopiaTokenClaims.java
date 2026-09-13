package com.quizopia.identity.security.token;

public final class QuizopiaTokenClaims {
    public static final String PRINCIPAL_TYPE = "principal_type";
    public static final String ROLES = "roles";
    public static final String SCOPE = "scope";

    public static final String USER = "USER";
    public static final String SERVICE = "SERVICE";

    public static final String USER_AUTHORITY = "TOKEN_USER";
    public static final String SERVICE_AUTHORITY = "TOKEN_SERVICE";
    public static final String ROLE_AUTHORITY_PREFIX = "ROLE_";
    public static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    private QuizopiaTokenClaims() {}
}

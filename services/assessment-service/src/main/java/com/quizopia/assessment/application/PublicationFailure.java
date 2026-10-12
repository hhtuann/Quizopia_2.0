package com.quizopia.assessment.application;

public final class PublicationFailure extends RuntimeException {
    private final int status;
    private final String code;

    public PublicationFailure(int status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static PublicationFailure conflict() {
        return new PublicationFailure(409, "PUBLICATION_CONFLICT");
    }

    public static PublicationFailure missing() {
        return new PublicationFailure(404, "PUBLICATION_NOT_FOUND");
    }

    public static PublicationFailure dependency() {
        return new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
    }
}

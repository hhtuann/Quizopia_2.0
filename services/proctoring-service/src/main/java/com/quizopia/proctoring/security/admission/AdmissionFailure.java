package com.quizopia.proctoring.security.admission;

public final class AdmissionFailure extends RuntimeException {
    private final int status;
    private final String code;

    private AdmissionFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AdmissionFailure unavailable() {
        return new AdmissionFailure(503, "AUTHORITY_UNAVAILABLE");
    }

    public static AdmissionFailure invalidUser() {
        return new AdmissionFailure(401, "UNAUTHENTICATED");
    }

    public static AdmissionFailure forbidden() {
        return new AdmissionFailure(403, "ACCESS_DENIED");
    }
}

package com.quizopia.identity.application.admission;

public final class AdmissionFailure extends RuntimeException {
    private final int status;
    private final String code;

    public AdmissionFailure(int status, String code) {
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

    public static AdmissionFailure unavailable() {
        return new AdmissionFailure(503, "AUTHORITY_UNAVAILABLE");
    }

    public static AdmissionFailure invalidUser() {
        return new AdmissionFailure(401, "USER_EVIDENCE_INVALID");
    }

    public static AdmissionFailure malformed() {
        return new AdmissionFailure(400, "INVALID_ADMISSION_REQUEST");
    }
}

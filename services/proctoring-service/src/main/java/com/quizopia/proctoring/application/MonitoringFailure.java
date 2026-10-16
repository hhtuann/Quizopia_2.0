package com.quizopia.proctoring.application;

public final class MonitoringFailure extends RuntimeException {
    private final int status;

    public MonitoringFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String code() {
        return getMessage();
    }

    public static MonitoringFailure unavailable() {
        return new MonitoringFailure(503, "AUTHORITY_UNAVAILABLE");
    }
}

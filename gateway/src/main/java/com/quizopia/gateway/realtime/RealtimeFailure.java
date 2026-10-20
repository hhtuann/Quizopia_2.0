package com.quizopia.gateway.realtime;

final class RealtimeFailure extends RuntimeException {
    enum Kind {
        MALFORMED(4400),
        AUTHENTICATION(4401),
        ACCESS_DENIED(4403),
        AUTH_TIMEOUT(4408),
        AUTHORITY_UNAVAILABLE(4503);

        private final int closeCode;

        Kind(int closeCode) {
            this.closeCode = closeCode;
        }

        int closeCode() {
            return closeCode;
        }
    }

    private final Kind kind;

    private RealtimeFailure(Kind kind) {
        super(kind.name(), null, false, false);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    static RealtimeFailure malformed() {
        return new RealtimeFailure(Kind.MALFORMED);
    }

    static RealtimeFailure authentication() {
        return new RealtimeFailure(Kind.AUTHENTICATION);
    }

    static RealtimeFailure accessDenied() {
        return new RealtimeFailure(Kind.ACCESS_DENIED);
    }

    static RealtimeFailure timeout() {
        return new RealtimeFailure(Kind.AUTH_TIMEOUT);
    }

    static RealtimeFailure authorityUnavailable() {
        return new RealtimeFailure(Kind.AUTHORITY_UNAVAILABLE);
    }
}

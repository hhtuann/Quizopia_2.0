package com.quizopia.identity.application.refresh;

import java.util.Objects;
import java.util.Optional;

public final class RefreshAccessResult {
    private static final RefreshAccessResult REJECTED = new RefreshAccessResult(RefreshAccessStatus.REJECTED, null);

    private final RefreshAccessStatus status;
    private final RefreshedBrowserSession session;

    private RefreshAccessResult(RefreshAccessStatus status, RefreshedBrowserSession session) {
        this.status = Objects.requireNonNull(status, "status");
        this.session = session;
    }

    public static RefreshAccessResult refreshed(RefreshedBrowserSession session) {
        return new RefreshAccessResult(RefreshAccessStatus.REFRESHED, Objects.requireNonNull(session, "session"));
    }

    public static RefreshAccessResult rejected() {
        return REJECTED;
    }

    public RefreshAccessStatus status() {
        return status;
    }

    public Optional<RefreshedBrowserSession> session() {
        return Optional.ofNullable(session);
    }

    @Override
    public String toString() {
        return "RefreshAccessResult{status=" + status + ", sessionPresent=" + (session != null) + '}';
    }
}

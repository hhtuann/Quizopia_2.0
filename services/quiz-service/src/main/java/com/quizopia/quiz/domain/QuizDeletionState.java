package com.quizopia.quiz.domain;

import java.util.List;

/**
 * Forward-only, durable states of a quiz deletion operation. Main states advance strictly along
 * {@link #MAIN_ORDER}; the remaining nonterminal states pause an operation without losing its
 * main progress, and {@link #ALL_COPIES_ERASED} is the irreversible terminal boundary.
 */
public enum QuizDeletionState {
    REQUESTED(0),
    QUIZ_FENCED(1),
    DEPENDENCIES_FENCING(2),
    ACCESS_REVOKED(3),
    PURGE_PENDING(4),
    PURGING(5),
    PRIMARY_PURGED(6),
    ALL_COPIES_ERASED(7),
    WAITING_DEPENDENCY(-1),
    BLOCKED_BY_RETENTION(-1),
    PENDING_POLICY(-1),
    RETRYABLE_FAILURE(-1);

    public static final List<QuizDeletionState> MAIN_ORDER = List.of(
            REQUESTED,
            QUIZ_FENCED,
            DEPENDENCIES_FENCING,
            ACCESS_REVOKED,
            PURGE_PENDING,
            PURGING,
            PRIMARY_PURGED,
            ALL_COPIES_ERASED);

    private final int mainOrder;

    QuizDeletionState(int mainOrder) {
        this.mainOrder = mainOrder;
    }

    public boolean isMain() {
        return mainOrder >= 0;
    }

    public int mainOrder() {
        return mainOrder;
    }

    public static boolean isForwardTransition(QuizDeletionState current, int mainProgress, QuizDeletionState target) {
        if (target == REQUESTED || current == ALL_COPIES_ERASED) {
            return false;
        }
        if (!target.isMain()) {
            return true;
        }
        if (target.mainOrder < mainProgress) {
            return false;
        }
        return target.mainOrder > mainProgress || !current.isMain();
    }
}

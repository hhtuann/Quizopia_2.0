package com.quizopia.assessment.persistence;
/** Recognize only the fixed V5 rollback marker; never disclose database messages. */
final class ClosureCutoffConflict {
    private ClosureCutoffConflict() {}

    static boolean matches(Throwable failure) {
        for (int i = 0; failure != null && i < 8; i++, failure = failure.getCause()) {
            if (failure instanceof java.sql.SQLException sql && "QZ001".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}

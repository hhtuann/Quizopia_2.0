package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.attempt.*;
import java.util.Optional;
import java.util.UUID;

/** Internal read foundation only. Later use cases must perform their own current authority/owner checks. */
public interface AttemptPersistence {
    Optional<AttemptRecord> find(UUID attemptId);

    Optional<StoredResult> result(UUID attemptId);

    Optional<OperationReceipt> receipt(UUID attemptId, String keyDigest);
}

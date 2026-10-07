package com.quizopia.identity.application.teacherenablement;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TeacherEnablementService {
    private final TeacherEnablementTransaction transaction;

    public TeacherEnablementService(TeacherEnablementTransaction transaction) {
        this.transaction = transaction;
    }

    public TeacherEnablementStatus enable(UUID authenticatedUserId) {
        return transaction.enable(Objects.requireNonNull(authenticatedUserId, "authenticatedUserId"));
    }
}

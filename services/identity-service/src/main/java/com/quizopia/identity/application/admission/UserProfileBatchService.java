package com.quizopia.identity.application.admission;

import com.quizopia.identity.application.account.AccountLifecycleStatus;
import com.quizopia.identity.configuration.UserProfileProperties;
import com.quizopia.identity.persistence.entity.UserAccountEntity;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded internal batch profile resolution. One repository query per request (no per-id N+1),
 * unknown or non-ACTIVE accounts are omitted entirely, and displayName is the stored display name
 * when present, else the username. Output is sorted by studentId for deterministic merging and
 * never contains email, roles, or status.
 */
@Service
@Profile("!test")
public class UserProfileBatchService {
    private static final Comparator<StudentProfile> BY_STUDENT_ID = Comparator.comparing(StudentProfile::studentId);

    private final java.util.concurrent.Semaphore capacity;
    private final UserAccountRepository accounts;

    public UserProfileBatchService(UserAccountRepository accounts, UserProfileProperties properties) {
        this.capacity = new java.util.concurrent.Semaphore(properties.maxConcurrent());
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Response resolve(List<UUID> studentIds) {
        if (!capacity.tryAcquire()) throw AdmissionFailure.unavailable();
        try {
            List<StudentProfile> items = accounts.findAllById(studentIds).stream()
                    .filter(account -> isActive(account))
                    .map(account -> new StudentProfile(account.getId(), resolveDisplayName(account)))
                    .sorted(BY_STUDENT_ID)
                    .toList();
            return new Response(items);
        } catch (RuntimeException e) {
            throw AdmissionFailure.unavailable();
        } finally {
            capacity.release();
        }
    }

    private static boolean isActive(UserAccountEntity account) {
        return AccountLifecycleStatus.ACTIVE.equals(account.getAccountStatus());
    }

    private static String resolveDisplayName(UserAccountEntity account) {
        return account.getDisplayName() != null && !account.getDisplayName().isBlank()
                ? account.getDisplayName()
                : account.getUsername();
    }

    public record Response(List<StudentProfile> items) {
        public Response {
            items = List.copyOf(items);
        }

        public String toString() {
            return "StudentProfileBatch[redacted]";
        }
    }
}

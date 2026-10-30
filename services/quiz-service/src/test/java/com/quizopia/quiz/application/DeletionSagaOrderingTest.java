package com.quizopia.quiz.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.quiz.domain.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeletionSagaOrderingTest {
    final QuizDeletionOperationRepository repository = mock(QuizDeletionOperationRepository.class);
    final DeletionOperationService operations = mock(DeletionOperationService.class);
    final DeletionDependencies dependencies = mock(DeletionDependencies.class);
    final URI assessment = URI.create("http://assessment");
    final URI proctoring = URI.create("http://proctoring");
    final UUID operationId = UUID.randomUUID();
    final UUID quizId = UUID.randomUUID();
    final DeletionDependencies.Dependents inventory =
            new DeletionDependencies.Dependents(List.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
    final QuizDeletionOperation operation = new QuizDeletionOperation(
            operationId,
            quizId,
            UUID.randomUUID(),
            "test-key",
            QuizDeletionState.PURGING,
            5,
            Instant.now(),
            Instant.now(),
            "title",
            List.of(),
            null);
    final DeletionSagaService saga =
            new DeletionSagaService(repository, operations, dependencies, DeletionSagaObserver.NOOP, 10);

    DeletionDependencies.PurgeStatus status(boolean purged, boolean hold) {
        return new DeletionDependencies.PurgeStatus(Instant.now(), purged, hold);
    }

    List<DeletionDependencies.PurgeOutcome> outcome(String status) {
        return List.of(new DeletionDependencies.PurgeOutcome(operationId, quizId, status));
    }

    @BeforeEach
    void setup() {
        when(repository.findSagaCandidates(10)).thenReturn(List.of(operation));
        when(repository.findById(operationId)).thenReturn(Optional.of(operation));
        when(dependencies.assessmentBaseUrl()).thenReturn(assessment);
        when(dependencies.proctoringBaseUrl()).thenReturn(proctoring);
        when(dependencies.purgeStatus(assessment, quizId)).thenReturn(status(false, false));
        when(dependencies.purgeStatus(proctoring, quizId)).thenReturn(status(false, false));
        when(dependencies.assessmentDependents(assessment, quizId)).thenReturn(inventory);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "IN_PROGRESS",
                "PENDING_PROVIDER",
                "INVENTORY_MISMATCH",
                "RECONCILIATION_BLOCKED",
                "BLOCKED_BY_RETENTION",
                "WAITING_REVOCATION_ACK",
                "UNKNOWN"
            })
    void unfinishedProctoringNeverDestroysAssessment(String pending) {
        when(dependencies.purgeBatch(eq(proctoring), anyList(), eq(inventory))).thenReturn(outcome(pending));
        saga.reconcileDue();
        verify(dependencies, never()).purgeBatch(eq(assessment), anyList());
        verify(operations, never()).completePrimaryPurge(any(), any());
        verify(operations).advanceState(eq(operationId), any(), anyString());
    }

    @Test
    void bothOwnerHoldsArePreflightedBeforeAnyDestruction() {
        for (URI heldOwner : List.of(assessment, proctoring)) {
            when(dependencies.purgeStatus(heldOwner, quizId)).thenReturn(status(false, true));
            saga.reconcileDue();
            verify(dependencies, never()).purgeBatch(any(), anyList());
            verify(dependencies, never()).purgeBatch(any(), anyList(), any());
            when(dependencies.purgeStatus(heldOwner, quizId)).thenReturn(status(false, false));
        }
    }

    @Test
    void emptyDuplicateAndWrongIdentityReceiptsNeverAuthorizeAssessment() {
        var valid = outcome("PURGED").getFirst();
        var wrongOperation = new DeletionDependencies.PurgeOutcome(UUID.randomUUID(), quizId, "PURGED");
        var wrongQuiz = new DeletionDependencies.PurgeOutcome(operationId, UUID.randomUUID(), "PURGED");
        for (List<DeletionDependencies.PurgeOutcome> receipt : List.of(
                List.<DeletionDependencies.PurgeOutcome>of(),
                List.of(valid, valid),
                List.of(wrongOperation),
                List.of(wrongQuiz))) {
            when(dependencies.purgeBatch(eq(proctoring), anyList(), eq(inventory)))
                    .thenReturn(receipt);
            saga.reconcileDue();
        }
        verify(dependencies, never()).purgeBatch(eq(assessment), anyList());
        verify(operations, times(4))
                .advanceState(operationId, QuizDeletionState.WAITING_DEPENDENCY, "invalid-purge-receipt");
    }

    @Test
    void purgedProctoringIsNotReplayedWithShrunkenInventoryOnAssessmentRetries() {
        when(dependencies.purgeStatus(proctoring, quizId)).thenReturn(status(true, false));
        when(dependencies.purgeBatch(eq(assessment), anyList())).thenReturn(outcome("IN_PROGRESS"), outcome("PURGED"));
        saga.reconcileDue();
        verify(operations, never()).completePrimaryPurge(any(), any());
        when(dependencies.purgeStatus(assessment, quizId)).thenReturn(status(true, false));
        saga.reconcileDue();
        verify(dependencies, never()).assessmentDependents(any(), any());
        verify(dependencies, never()).purgeBatch(eq(proctoring), anyList(), any());
        verify(operations).completePrimaryPurge(operationId, quizId);
    }

    @Test
    void proctoringReceiptAndStatusMustBothPrecedeAssessmentPurge() {
        when(dependencies.purgeStatus(proctoring, quizId)).thenReturn(status(false, false), status(true, false));
        when(dependencies.purgeBatch(eq(proctoring), anyList(), eq(inventory))).thenReturn(outcome("PURGED"));
        when(dependencies.purgeBatch(eq(assessment), anyList())).thenReturn(outcome("PURGED"));
        when(dependencies.purgeStatus(assessment, quizId)).thenReturn(status(false, false), status(true, false));
        saga.reconcileDue();
        var order = inOrder(dependencies, operations);
        order.verify(dependencies).purgeStatus(assessment, quizId);
        order.verify(dependencies).purgeStatus(proctoring, quizId);
        order.verify(dependencies).assessmentDependents(assessment, quizId);
        order.verify(dependencies).purgeBatch(eq(proctoring), anyList(), eq(inventory));
        order.verify(dependencies).purgeStatus(proctoring, quizId);
        order.verify(dependencies).purgeBatch(eq(assessment), anyList());
        order.verify(operations).completePrimaryPurge(operationId, quizId);
    }
}

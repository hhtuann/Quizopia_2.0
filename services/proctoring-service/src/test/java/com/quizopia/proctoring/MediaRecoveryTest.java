package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.proctoring.media.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Recovery port tests; no assertion of provider-side revocation or outage termination. */
class MediaRecoveryTest {
    MediaStore store;
    MediaProvider provider;
    SimpleMeterRegistry meters;
    MediaRecovery recovery;
    MediaStore.Lease pending;

    @BeforeEach
    void fixture() {
        store = mock(MediaStore.class);
        provider = mock(MediaProvider.class);
        meters = new SimpleMeterRegistry();
        recovery = new MediaRecovery(store, provider, meters);
        pending = new MediaStore.Lease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "STUDENT",
                "opaque-room",
                "opaque-participant",
                Instant.now().minusSeconds(1),
                "REVOKE_PENDING");
        when(provider.qualified()).thenReturn(true);
        when(store.pending(10)).thenReturn(List.of(pending));
        when(store.fence(pending.id(), null)).thenReturn(pending);
        when(store.endedRooms(10)).thenReturn(List.of());
    }

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    double count(String outcome) {
        return meters.counter("proctoring.media.recovery", "outcome", outcome).count();
    }

    @Test
    void unqualifiedProviderCannotPretendRecoverySucceeded() {
        when(provider.qualified()).thenReturn(false);
        recovery.tick();
        verifyNoInteractions(store);
        verify(provider, never()).revoke(any(), any(), any());
        assertEquals(0, count("revoked"));
    }

    @Test
    void localFencePrecedesProviderCallAndDurableCompletion() {
        Instant started = Instant.now();
        recovery.tick();
        var ordered = inOrder(store, provider);
        ordered.verify(store).pending(10);
        ordered.verify(store).fence(pending.id(), null);
        ordered.verify(provider)
                .revoke(
                        eq(pending.room()),
                        eq(pending.identity()),
                        argThat(cutoff -> cutoff.isAfter(started.plusSeconds(8))));
        ordered.verify(store).revoked(pending.id());
        assertEquals(1, count("revoked"));
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void providerOutagePreservesPendingIntentAndNextInstanceRetries() {
        doThrow(new IllegalStateException("synthetic provider outage"))
                .doNothing()
                .when(provider)
                .revoke(any(), any(), any());
        recovery.tick();
        verify(store, never()).revoked(any());
        assertEquals(1, count("pending"));
        new MediaRecovery(store, provider, meters).tick();
        verify(provider, times(2)).revoke(eq(pending.room()), eq(pending.identity()), any(Instant.class));
        verify(store).revoked(pending.id());
        assertEquals(1, count("revoked"));
    }

    @Test
    void uncertainProviderSuccessAndUncertainDatabaseReceiptBothRemainRetryable() {
        AtomicInteger effects = new AtomicInteger();
        doAnswer(invocation -> {
                    if (effects.incrementAndGet() == 1)
                        throw new IllegalStateException("synthetic lost provider acknowledgment after effect");
                    return null;
                })
                .when(provider)
                .revoke(any(), any(), any());
        doThrow(new IllegalStateException("synthetic lost database acknowledgment"))
                .doNothing()
                .when(store)
                .revoked(pending.id());
        recovery.tick();
        recovery.tick();
        recovery.tick();
        assertEquals(3, effects.get());
        verify(store, times(2)).revoked(pending.id());
        assertEquals(2, count("pending"));
        assertEquals(1, count("revoked"));
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void renewedCandidateIsRecheckedUnderFenceAndNotRevokedFromStaleScan() {
        var renewed = new MediaStore.Lease(
                pending.id(),
                pending.sessionId(),
                pending.actorId(),
                pending.role(),
                pending.room(),
                pending.identity(),
                Instant.now().plusSeconds(20),
                "ACTIVE");
        when(store.fence(pending.id(), null)).thenReturn(renewed);
        recovery.tick();
        verify(provider, never()).revoke(any(), any(), any());
        verify(store, never()).revoked(any());
        assertEquals(0, count("revoked"));
    }

    @Test
    void alreadyCompletedGrantIsNotReactivatedOrRevokedAgain() {
        var completed = new MediaStore.Lease(
                pending.id(),
                pending.sessionId(),
                pending.actorId(),
                pending.role(),
                pending.room(),
                pending.identity(),
                pending.leaseUntil(),
                "REVOKED");
        when(store.fence(pending.id(), null)).thenReturn(completed);
        recovery.tick();
        verify(provider, never()).revoke(any(), any(), any());
        verify(store, never()).renew(any(), any(), any(), any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void twoWorkerInstancesCanRepeatSameIrreversibleProviderIntentWithoutMinting() throws Exception {
        var both = new CountDownLatch(2);
        doAnswer(invocation -> {
                    both.countDown();
                    assertTrue(both.await(3, TimeUnit.SECONDS));
                    return null;
                })
                .when(provider)
                .revoke(any(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(recovery::tick);
            var second = executor.submit(new MediaRecovery(store, provider, meters)::tick);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        verify(provider, times(2)).revoke(eq(pending.room()), eq(pending.identity()), any(Instant.class));
        verify(store, times(2)).revoked(pending.id());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void providerRoomDeletionFailureDoesNotMarkRoomDeletedAndRetries() {
        when(store.pending(10)).thenReturn(List.of());
        when(store.endedRooms(10)).thenReturn(List.of("ended-room"));
        doThrow(new IllegalStateException("synthetic delete outage"))
                .doNothing()
                .when(provider)
                .deleteRoom("ended-room");
        recovery.tick();
        verify(store, never()).roomDeleted(any());
        recovery.tick();
        verify(provider, times(2)).deleteRoom("ended-room");
        verify(store).roomDeleted("ended-room");
        assertEquals(1, count("pending"));
    }
}

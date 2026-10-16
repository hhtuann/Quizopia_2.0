package com.quizopia.proctoring.media;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/** At most ten attempts per tick; retries preserve pending intent and never mint credentials. */
public final class MediaRecovery {
    private final MediaStore store;
    private final MediaProvider provider;
    private final MeterRegistry meters;

    public MediaRecovery(MediaStore store, MediaProvider provider, MeterRegistry meters) {
        this.store = store;
        this.provider = provider;
        this.meters = meters;
    }

    public void tick() {
        if (!provider.qualified()) return;
        for (var candidate : store.pending(10)) {
            try {
                var lease = store.fence(candidate.id(), null);
                if (!lease.status().equals("REVOKE_PENDING")) continue;
                // A current cutoff with bounded transport/clock headroom is required by the provider on every retry,
                // including after restart.
                provider.revoke(
                        lease.room(),
                        lease.identity(),
                        Instant.ofEpochSecond(Instant.now().getEpochSecond() + 10));
                store.revoked(lease.id());
                meters.counter("proctoring.media.recovery", "outcome", "revoked")
                        .increment();
            } catch (RuntimeException unavailable) {
                meters.counter("proctoring.media.recovery", "outcome", "pending")
                        .increment();
            }
        }
        for (String room : store.endedRooms(10)) {
            try {
                provider.deleteRoom(room);
                store.roomDeleted(room);
            } catch (RuntimeException unavailable) {
                meters.counter("proctoring.media.recovery", "outcome", "pending")
                        .increment();
            }
        }
    }
}

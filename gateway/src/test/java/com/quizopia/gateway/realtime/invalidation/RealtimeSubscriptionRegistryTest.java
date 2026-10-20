package com.quizopia.gateway.realtime.invalidation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Saturation, rollback, bucket-leak and concurrency guarantees for the bounded registry. */
class RealtimeSubscriptionRegistryTest {
    static final RealtimeSubscriptionRegistry.Listener IGNORED = (eventId, occurredAt) -> {};

    static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void saturationRejectsFurtherRegistrationsWithoutLeakingState() {
        var registry = new RealtimeSubscriptionRegistry(3);
        assertTrue(registry.register("ATTEMPT_OWNER", id(), "s1", IGNORED));
        assertTrue(registry.register("ATTEMPT_OWNER", id(), "s2", IGNORED));
        assertTrue(registry.register("PUBLICATION_TEACHER", id(), "s3", IGNORED));
        assertEquals(3, registry.size());
        assertEquals(3, registry.resourceCount());

        String newResource = id();
        for (int attempt = 0; attempt < 10; attempt++)
            assertFalse(registry.register("ATTEMPT_OWNER", newResource, "rejected-" + attempt, IGNORED));
        // Repeated rejected resource ids never leave phantom buckets or entries behind.
        assertEquals(3, registry.size());
        assertEquals(3, registry.resourceCount());
        assertEquals(10, registry.registryFullRejections());

        // A rejected registration on an already-bucketed resource keeps that bucket intact.
        assertTrue(registry.unregister("s1"));
        assertEquals(2, registry.resourceCount(), "The emptied bucket must be removed");
        String existing = id();
        assertTrue(registry.register("ATTEMPT_OWNER", existing, "holder", IGNORED));
        assertFalse(registry.register("ATTEMPT_OWNER", existing, "overflow", IGNORED));
        assertEquals(3, registry.size());
        assertEquals(3, registry.resourceCount());
        assertTrue(
                registry.invalidate("ATTEMPT_OWNER", existing, UUID.randomUUID().toString(), Instant.now()) >= 1);
    }

    @Test
    void duplicateIdentifierIsRejectedAndNeverRebinds() {
        var registry = new RealtimeSubscriptionRegistry(2);
        String resource = id();
        assertTrue(registry.register("ATTEMPT_OWNER", resource, "same", IGNORED));
        AtomicInteger calls = new AtomicInteger();
        assertFalse(registry.register(
                "PUBLICATION_TEACHER", id(), "same", (eventId, occurredAt) -> calls.incrementAndGet()));
        registry.invalidate("ATTEMPT_OWNER", resource, "event", Instant.now());
        assertEquals(0, calls.get(), "The original binding must stay authoritative");
    }

    @Test
    void unregisterReleasesCapacityAndEmptyBuckets() {
        var registry = new RealtimeSubscriptionRegistry(1);
        String resource = id();
        assertTrue(registry.register("ATTEMPT_OWNER", resource, "only", IGNORED));
        assertEquals(1, registry.size());
        assertEquals(1, registry.resourceCount());
        assertTrue(registry.unregister("only"));
        assertEquals(0, registry.size());
        assertEquals(0, registry.resourceCount(), "The emptied bucket must be removed");
        assertTrue(registry.register("ATTEMPT_OWNER", id(), "next", IGNORED), "Capacity must recover");
        assertFalse(registry.unregister("missing"));
    }

    @Test
    void concurrentRegistrationAndCleanupStaysBoundedAndLeakFree() throws Exception {
        int bound = 64;
        var registry = new RealtimeSubscriptionRegistry(bound);
        AtomicInteger observedMaximum = new AtomicInteger();
        int workers = 8;
        int perWorker = 200;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                final int seed = worker;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> registered = new ArrayList<>();
                    for (int round = 0; round < perWorker; round++) {
                        String subscription = "w" + seed + "-" + round;
                        if (registry.register("ATTEMPT_OWNER", id(), subscription, IGNORED))
                            registered.add(subscription);
                        else if (!registered.isEmpty()) {
                            String released = registered.removeFirst();
                            registry.unregister(released);
                        }
                        int size = registry.size();
                        observedMaximum.accumulateAndGet(size, Math::max);
                    }
                    registered.forEach(registry::unregister);
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(observedMaximum.get() <= bound, "Registry exceeded its bound: " + observedMaximum.get());
        assertEquals(0, registry.size(), "All registrations must be cleaned up");
        assertEquals(0, registry.resourceCount(), "No resource bucket may leak");
    }
}

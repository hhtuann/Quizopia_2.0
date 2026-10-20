package com.quizopia.gateway.realtime.invalidation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded instance-local registry: resource key to currently subscribed connections. Hints fan out
 * only to this instance's own subscriptions; the registry is never authorization evidence.
 */
public final class RealtimeSubscriptionRegistry {
    public interface Listener {
        void invalidated(String eventId, Instant occurredAt);
    }

    private final int maximum;
    private final Map<String, Map<String, Listener>> byResource = new ConcurrentHashMap<>();
    private final Map<String, String> subscriptionResources = new ConcurrentHashMap<>();
    private final AtomicLong rejections = new AtomicLong();

    public RealtimeSubscriptionRegistry(int maximum) {
        this.maximum = maximum;
    }

    /** Returns false when the registry is full; the subscription then simply receives no hints. */
    public boolean register(String kind, String resourceId, String subscriptionId, Listener listener) {
        String resourceKey = kind + ":" + resourceId;
        var existing = byResource.computeIfAbsent(resourceKey, ignored -> new ConcurrentHashMap<>());
        if (existing.putIfAbsent(subscriptionId, listener) == null) {
            if (subscriptionResources.putIfAbsent(subscriptionId, resourceKey) != null) {
                existing.remove(subscriptionId);
                return false;
            }
            if (subscriptionResources.size() > maximum) {
                subscriptionResources.remove(subscriptionId);
                existing.remove(subscriptionId);
                rejections.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    public boolean unregister(String subscriptionId) {
        String resourceKey = subscriptionResources.remove(subscriptionId);
        if (resourceKey == null) return false;
        var listeners = byResource.get(resourceKey);
        if (listeners != null) {
            listeners.remove(subscriptionId);
            if (listeners.isEmpty()) byResource.remove(resourceKey, listeners);
        }
        return true;
    }

    /** Returns how many local subscriptions matched; iterates a snapshot safe against mutation. */
    public int invalidate(String kind, String resourceId, String eventId, Instant occurredAt) {
        var listeners = byResource.get(kind + ":" + resourceId);
        if (listeners == null || listeners.isEmpty()) return 0;
        for (Listener listener : List.copyOf(listeners.values())) {
            try {
                listener.invalidated(eventId, occurredAt);
            } catch (RuntimeException ignored) {
                // One broken connection never blocks the remaining fanout.
            }
        }
        return listeners.size();
    }

    public int size() {
        return subscriptionResources.size();
    }

    public long registryFullRejections() {
        return rejections.get();
    }
}

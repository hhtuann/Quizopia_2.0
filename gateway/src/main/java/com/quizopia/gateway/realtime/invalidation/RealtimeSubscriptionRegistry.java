package com.quizopia.gateway.realtime.invalidation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded instance-local registry: resource key to currently subscribed connections. Hints fan out
 * only to this instance's own subscriptions; the registry is never authorization evidence.
 * Mutations are serialized so the entry bound and bucket lifecycle hold under concurrency; a
 * rejected registration never leaves an empty resource bucket behind.
 */
public final class RealtimeSubscriptionRegistry {
    public interface Listener {
        void invalidated(String eventId, Instant occurredAt);
    }

    private final int maximum;
    private final Map<String, Map<String, Listener>> byResource = new HashMap<>();
    private final Map<String, String> subscriptionResources = new HashMap<>();
    private long rejections;

    public RealtimeSubscriptionRegistry(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Registry bound must be positive");
        this.maximum = maximum;
    }

    /**
     * Returns false when the registry is full or the id is already bound; the caller must then
     * withhold the subscription instead of receiving hints it can no longer be told about.
     */
    public synchronized boolean register(String kind, String resourceId, String subscriptionId, Listener listener) {
        if (subscriptionResources.containsKey(subscriptionId)) {
            rejections++;
            return false;
        }
        if (subscriptionResources.size() >= maximum) {
            rejections++;
            return false;
        }
        String resourceKey = kind + ":" + resourceId;
        Map<String, Listener> bucket = byResource.get(resourceKey);
        boolean createdBucket = false;
        if (bucket == null) {
            bucket = new HashMap<>();
            createdBucket = true;
        }
        bucket.put(subscriptionId, listener);
        if (createdBucket) byResource.put(resourceKey, bucket);
        subscriptionResources.put(subscriptionId, resourceKey);
        return true;
    }

    public synchronized boolean unregister(String subscriptionId) {
        String resourceKey = subscriptionResources.remove(subscriptionId);
        if (resourceKey == null) return false;
        var bucket = byResource.get(resourceKey);
        if (bucket != null) {
            bucket.remove(subscriptionId);
            if (bucket.isEmpty()) byResource.remove(resourceKey);
        }
        return true;
    }

    /** Returns how many local subscriptions matched; listeners run outside the registry lock. */
    public int invalidate(String kind, String resourceId, String eventId, Instant occurredAt) {
        List<Listener> listeners;
        synchronized (this) {
            var bucket = byResource.get(kind + ":" + resourceId);
            listeners = bucket == null || bucket.isEmpty() ? List.of() : List.copyOf(bucket.values());
        }
        for (Listener listener : listeners) {
            try {
                listener.invalidated(eventId, occurredAt);
            } catch (RuntimeException ignored) {
                // One broken connection never blocks the remaining fanout.
            }
        }
        return listeners.size();
    }

    /** Test and operations hygiene only; normal lifecycle uses unsubscribe and teardown. */
    public synchronized void clear() {
        byResource.clear();
        subscriptionResources.clear();
    }

    public synchronized int size() {
        return subscriptionResources.size();
    }

    public synchronized int resourceCount() {
        return byResource.size();
    }

    public synchronized long registryFullRejections() {
        return rejections;
    }
}

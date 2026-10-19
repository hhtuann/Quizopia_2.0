package com.quizopia.gateway.realtime;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RealtimeConnectionLimiter {
    public enum Rejection {
        RATE,
        CAPACITY
    }

    public record Reservation(Lease lease, Rejection rejection) {
        static Reservation accepted(Lease lease) {
            return new Reservation(lease, null);
        }

        static Reservation rejected(Rejection rejection) {
            return new Reservation(null, rejection);
        }
    }

    public final class Lease implements AutoCloseable {
        private final String ip;
        private final String origin;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String ip, String origin) {
            this.ip = ip;
            this.origin = origin;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release(ip, origin);
        }
    }

    private final RealtimeProperties properties;
    private final Clock clock;
    private final int maximumUpgradeSources;
    private final Map<String, Integer> byIp = new HashMap<>();
    private final Map<String, Integer> byOrigin = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> upgrades = new HashMap<>();
    private int total;

    public RealtimeConnectionLimiter(RealtimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        properties.validate();
        maximumUpgradeSources = Math.multiplyExact(properties.getMaximumConnections(), 4);
    }

    public synchronized Reservation reserve(String ip, String origin) {
        long now = clock.millis();
        ArrayDeque<Long> recent = upgrades.get(ip);
        if (recent == null) {
            if (upgrades.size() >= maximumUpgradeSources) {
                removeExpiredUpgradeSources(now);
                if (upgrades.size() >= maximumUpgradeSources) {
                    return Reservation.rejected(Rejection.RATE);
                }
            }
            recent = new ArrayDeque<>();
            upgrades.put(ip, recent);
        }
        removeExpiredAttempts(recent, now);
        if (recent.size() >= properties.getUpgradeRatePerIp()) {
            return Reservation.rejected(Rejection.RATE);
        }
        recent.addLast(now);

        if (total >= properties.getMaximumConnections()
                || byIp.getOrDefault(ip, 0) >= properties.getMaximumConnectionsPerIp()
                || byOrigin.getOrDefault(origin, 0) >= properties.getMaximumConnectionsPerOrigin()) {
            return Reservation.rejected(Rejection.CAPACITY);
        }
        total++;
        byIp.merge(ip, 1, Integer::sum);
        byOrigin.merge(origin, 1, Integer::sum);
        return Reservation.accepted(new Lease(ip, origin));
    }

    private void removeExpiredUpgradeSources(long now) {
        Iterator<Map.Entry<String, ArrayDeque<Long>>> entries =
                upgrades.entrySet().iterator();
        while (entries.hasNext()) {
            ArrayDeque<Long> attempts = entries.next().getValue();
            removeExpiredAttempts(attempts, now);
            if (attempts.isEmpty()) entries.remove();
        }
    }

    private static void removeExpiredAttempts(ArrayDeque<Long> attempts, long now) {
        while (!attempts.isEmpty() && attempts.peekFirst() <= now - 60_000) attempts.removeFirst();
    }

    private synchronized void release(String ip, String origin) {
        total--;
        decrement(byIp, ip);
        decrement(byOrigin, origin);
    }

    private static void decrement(Map<String, Integer> counts, String key) {
        int next = counts.getOrDefault(key, 1) - 1;
        if (next == 0) counts.remove(key);
        else counts.put(key, next);
    }
}

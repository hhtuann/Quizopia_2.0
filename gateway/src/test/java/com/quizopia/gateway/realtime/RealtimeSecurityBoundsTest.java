package com.quizopia.gateway.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RealtimeSecurityBoundsTest {
    @Test
    void realtimeCapabilityIsDefaultOff() {
        assertEquals(false, new RealtimeProperties().isEnabled());
    }

    @Test
    void invalidRuntimeBoundsFailStartupValidation() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setAuthTimeout(Duration.ofSeconds(16));
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setAuthTimeout(Duration.ofSeconds(5));
        properties.setMaximumConnectionsPerIp(513);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void limiterAppliesRateAndConnectionCapacityBeforeUpgrade() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setMaximumConnections(1);
        properties.setMaximumConnectionsPerIp(1);
        properties.setMaximumConnectionsPerOrigin(1);
        properties.setUpgradeRatePerIp(2);
        var limiter = new RealtimeConnectionLimiter(
                properties, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));

        var accepted = limiter.reserve("127.0.0.1", "https://quizopia.test");
        assertNotNull(accepted.lease());
        assertNull(accepted.rejection());
        var capacity = limiter.reserve("127.0.0.1", "https://quizopia.test");
        assertEquals(RealtimeConnectionLimiter.Rejection.CAPACITY, capacity.rejection());
        accepted.lease().close();
        var rate = limiter.reserve("127.0.0.1", "https://quizopia.test");
        assertEquals(RealtimeConnectionLimiter.Rejection.RATE, rate.rejection());
    }

    @Test
    void limiterFailsClosedWhenUpgradeSourceTrackingReachesItsBound() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setMaximumConnections(1);
        properties.setMaximumConnectionsPerIp(1);
        properties.setMaximumConnectionsPerOrigin(1);
        var limiter = new RealtimeConnectionLimiter(
                properties, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));

        for (int source = 1; source <= 4; source++) {
            var reservation = limiter.reserve("192.0.2." + source, "https://quizopia.test");
            assertNotNull(reservation.lease());
            reservation.lease().close();
        }

        var bounded = limiter.reserve("192.0.2.5", "https://quizopia.test");
        assertEquals(RealtimeConnectionLimiter.Rejection.RATE, bounded.rejection());
    }
}

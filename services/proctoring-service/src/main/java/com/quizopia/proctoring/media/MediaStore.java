package com.quizopia.proctoring.media;

import com.quizopia.proctoring.application.MonitoringAuthority;
import java.time.*;
import java.util.*;

public interface MediaStore {
    record Lease(
            UUID id,
            UUID sessionId,
            UUID actorId,
            String role,
            String room,
            String identity,
            Instant leaseUntil,
            String status) {}

    Lease prepare(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID session, Duration duration);

    Lease find(UUID grant);

    Lease activate(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID grant);

    Lease renew(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID grant, Duration duration);

    Lease fence(UUID grant, UUID actor);

    List<Lease> pending(int limit);

    void revoked(UUID grant);

    List<String> endedRooms(int limit);

    void roomDeleted(String room);
}

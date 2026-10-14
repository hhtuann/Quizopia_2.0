package com.quizopia.proctoring.application;

import com.quizopia.proctoring.domain.Monitoring.*;
import java.util.UUID;

public interface MonitoringStore {
    Session create(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority);

    Session find(UUID id);

    Session reconcile(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID id, boolean audit);

    Acknowledgment append(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID id, Event event);

    Page timeline(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot authority, UUID id, long after, int limit);
}

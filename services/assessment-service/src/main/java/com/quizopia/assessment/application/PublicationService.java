package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicationService {
    private final PublicationStore store;
    private final PublicationSources sources;

    public PublicationService(PublicationStore store, PublicationSources sources) {
        this.store = store;
        this.sources = sources;
    }

    public Publication read(UUID actor, UUID id) {
        return store.owned(actor, id);
    }

    private String prepare(UUID actor, PublicationConfiguration c, String evidence) {
        if (c.audience() == PublicationConfiguration.Audience.CLASS) sources.teacher(c.classroomId(), actor, evidence);
        return sources.snapshot(c, evidence);
    }

    public Publication create(UUID actor, String key, PublicationConfiguration c, String evidence) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) throw new PublicationFailure(400, "INVALID_REQUEST");
        var replay = store.replay(actor, key, c);
        if (replay != null) return replay;
        prepare(actor, c, evidence);
        return store.create(actor, key, c);
    }

    public Publication update(UUID actor, UUID id, long revision, PublicationConfiguration c, String evidence) {
        var p = store.owned(actor, id);
        draft(p, revision);
        prepare(actor, c, evidence);
        return store.update(actor, id, revision, c);
    }

    public Publication open(UUID actor, UUID id, long revision, String evidence) {
        var p = store.owned(actor, id);
        if (p.state() == Publication.State.OPEN && Long.valueOf(revision).equals(p.openedFromRevision())) return p;
        draft(p, revision);
        if (p.configuration().monitoringChoice() == PublicationConfiguration.MonitoringChoice.UNCONFIGURED)
            throw new PublicationFailure(400, "INVALID_REQUEST");
        var snapshot = prepare(actor, p.configuration(), evidence);
        return store.open(actor, id, revision, snapshot);
    }

    private static void draft(Publication p, long revision) {
        if (revision < 0 || p.state() != Publication.State.DRAFT || p.revision() != revision)
            throw PublicationFailure.conflict();
    }

    public Publication extend(UUID actor, UUID id, long revision, Instant endAt, int durationMinutes) {
        return store.extend(actor, id, revision, endAt, durationMinutes);
    }

    public Publication publicEntry(String locator) {
        if (locator == null || !locator.matches("[A-Za-z0-9_-]{43}"))
            throw new PublicationFailure(400, "INVALID_REQUEST");
        return store.publicEntry(locator);
    }

    public Publication classEntry(UUID actor, UUID id, String evidence) {
        var p = store.classEntry(id);
        sources.member(p.configuration().classroomId(), actor, evidence);
        return p;
    }
}

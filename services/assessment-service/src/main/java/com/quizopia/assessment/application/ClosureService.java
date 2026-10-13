package com.quizopia.assessment.application;

import com.quizopia.assessment.configuration.ClosureProperties;
import com.quizopia.assessment.domain.attempt.AttemptRecord.Reason;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ClosureService {
    private final ClosureStore store;
    private final ClosureDependencies dependencies;
    private final AttemptFinalization finalizer;
    private final ClosureProperties properties;

    public ClosureService(
            ClosureStore store,
            ClosureDependencies dependencies,
            AttemptFinalization finalizer,
            ClosureProperties properties) {
        this.store = store;
        this.dependencies = dependencies;
        this.finalizer = finalizer;
        this.properties = properties;
    }

    public ClosureViews.Reply command(ClosureStore.Teacher actor, ClosureStore.Command command) {
        return switch (command.action()) {
            case "REQUEST" -> {
                var replay = store.preflight(actor, command);
                if (replay != null) yield replay;
                dependencies.requireInbox();
                // Probe only a real notice recipient outside locks. Trusted false is an
                // accepted blocked-delivery condition, never a reason to omit membership.
                store.probeRecipient(actor, command.publicationId()).ifPresent(dependencies::eligible);
                yield store.request(actor, command);
            }
            case "CANCEL" -> store.cancel(actor, command);
            case "CLOSE" -> {
                var replay = store.preflight(actor, command);
                if (replay != null) yield replay;
                // Reconcile bounded timeouts in independent C1 root transactions, never force unlimited work.
                for (int i = 0; i < properties.unitBudget(); i++) {
                    var due = store.due(command.publicationId(), null);
                    if (due.isEmpty()) break;
                    finalizer.systemFinalize(due.get(), Reason.BOUNDED_TIMEOUT);
                }
                dependencies.teacher(actor);
                yield store.close(actor, command);
            }
            case "COMPLETE" -> {
                var operation = store.reserve(actor, command, properties.unitBudget());
                if (operation.acknowledged()) yield operation.reply();
                for (int attempts = 0; attempts < 100 && operation.used() < operation.budget(); attempts++) {
                    var due = store.due(command.publicationId(), command.requestId());
                    if (due.isEmpty()) break;
                    var outcome = finalizer.completeUnit(new AttemptFinalization.CompletionUnit(
                            command.publicationId(), command.requestId(), actor.id(), command.key(), due.get()));
                    if (outcome.kind() == AttemptFinalization.Kind.NOT_DUE
                            || outcome.kind() == AttemptFinalization.Kind.NOT_APPLICABLE) break;
                    operation = store.reserve(actor, command, properties.unitBudget());
                    if (operation.acknowledged()) yield operation.reply();
                }
                dependencies.teacher(actor);
                yield store.finish(actor, command);
            }
            default -> throw new PublicationFailure(400, "INVALID_REQUEST");
        };
    }

    public ClosureViews.Closure get(ClosureStore.Teacher actor, UUID publication, UUID request) {
        return store.get(actor, publication, request);
    }

    public ClosureViews.Page<ClosureViews.TeacherRow> results(
            ClosureStore.Teacher actor, UUID publication, int limit, String cursor) {
        return store.results(actor, publication, limit, cursor);
    }

    public ClosureViews.Page<ClosureViews.Notification> inbox(UUID actor, int limit, String cursor) {
        return store.inbox(actor, limit, cursor);
    }

    public ClosureViews.Notification notification(UUID actor, UUID id) {
        return store.notification(actor, id);
    }
}

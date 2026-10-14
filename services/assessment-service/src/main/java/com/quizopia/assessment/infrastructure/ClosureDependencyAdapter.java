package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.ClosureProperties;
import com.quizopia.assessment.security.admission.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public final class ClosureDependencyAdapter implements ClosureDependencies {
    private final ClosureProperties closure;
    private final IdentityRecipientClient recipients;
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties bounds;
    private final JwtDecoder decoder;
    private final RequestMappingHandlerMapping mappings;

    public ClosureDependencyAdapter(
            ClosureProperties closure,
            IdentityRecipientClient recipients,
            IdentityAdmissionClient admission,
            AdmissionProperties bounds,
            JwtDecoder decoder,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            org.springframework.core.env.Environment environment) {
        if (environment.matchesProfiles("prod")
                && closure.recipientEnabled()
                && (!bounds.isEnabled()
                        || !"https".equals(bounds.getIdentityBaseUrl().getScheme())
                        || closure.edgeBaseUrl() == null
                        || !"https".equals(closure.edgeBaseUrl().getScheme())))
            throw new IllegalStateException("Private recipient HTTPS is required in production");
        this.closure = closure;
        this.recipients = recipients;
        this.admission = admission;
        this.bounds = bounds;
        this.decoder = decoder;
        this.mappings = mappings;
    }

    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
    }

    @Override
    public void requireInbox() {
        outside();
        // Deployment attestation is valid only after real authenticated edge/topology verification.
        // Also require the actual local handlers, capability and owner-scoped projection at confirmation.
        var paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(m -> m.getPatternValues().stream())
                .collect(java.util.stream.Collectors.toSet());
        if (!closure.inboxEnabled()
                || !closure.edgeInboxVerified()
                || !closure.recipientEnabled()
                || !bounds.isEnabled()
                || !paths.contains("/api/assessments/notifications")
                || !paths.contains("/api/assessments/notifications/{notificationId}"))
            throw new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
        recipients.requireEdgeInbox(closure.edgeBaseUrl());
    }

    @Override
    public Eligibility eligible(UUID student) {
        outside();
        requireInbox();
        return recipients.eligible(student);
    }

    @Override
    public void teacher(ClosureStore.Teacher actor) {
        outside();
        try {
            var user = decoder.decode(actor.evidence());
            if (!actor.id().toString().equals(user.getSubject())) throw new IllegalArgumentException();
            admission.admit(user, "TEACHER").block(bounds.getTotalTimeout().plusMillis(100));
        } catch (AdmissionFailure e) {
            throw new PublicationFailure(e.status(), e.code());
        } catch (RuntimeException e) {
            throw new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
        }
    }
}

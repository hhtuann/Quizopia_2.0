package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.domain.Monitoring.Session;
import com.quizopia.proctoring.media.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Application/port tests: provider and current authority are explicit doubles, not Cloud acceptance. */
class MediaServiceTest {
    MonitoringStore sessions;
    MonitoringAuthority authority;
    MediaStore store;
    MediaProvider provider;
    MediaProperties properties;
    MediaService service;
    UUID student, teacher, sessionId, grantId, attempt, publication, classroom;
    Instant deadline;
    MonitoringAuthority.Actor actor;
    Session session;
    MediaStore.Lease lease;

    @BeforeEach
    void fixture() {
        sessions = mock(MonitoringStore.class);
        authority = mock(MonitoringAuthority.class);
        store = mock(MediaStore.class);
        provider = mock(MediaProvider.class);
        properties = new MediaProperties();
        properties.setWorkerEnabled(true);
        properties.setPublicUrl(URI.create("wss://media.example.invalid"));
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        grantId = UUID.randomUUID();
        attempt = UUID.randomUUID();
        publication = UUID.randomUUID();
        classroom = UUID.randomUUID();
        deadline = Instant.now().plusSeconds(300);
        actor = actor(student, Set.of("STUDENT"));
        session = new Session(
                sessionId, attempt, publication, classroom, student, deadline, "ACTIVE", Instant.now(), null);
        lease = new MediaStore.Lease(
                grantId,
                sessionId,
                student,
                "STUDENT",
                "opaque-room",
                "opaque-participant",
                Instant.now().plusSeconds(10),
                "ACTIVE");
        when(sessions.find(sessionId)).thenReturn(session);
        when(authority.check(actor, attempt, "STUDENT")).thenAnswer(invocation -> snapshot(student, "STUDENT", true));
        when(provider.qualified()).thenReturn(true);
        when(store.prepare(eq(actor), any(), eq(sessionId), any())).thenReturn(lease);
        when(store.activate(eq(actor), any(), eq(grantId))).thenReturn(lease);
        when(store.find(grantId)).thenReturn(lease);
        when(store.renew(eq(actor), any(), eq(grantId), any())).thenReturn(lease);
        when(provider.token(lease.room(), lease.identity(), lease.role(), lease.leaseUntil()))
                .thenReturn("synthetic-sensitive-media-token");
        service = new MediaService(sessions, authority, store, provider, properties);
    }

    MonitoringAuthority.Actor actor(UUID id, Set<String> roles) {
        return new MonitoringAuthority.Actor(
                id, roles, "synthetic-sensitive-user-evidence", Instant.now().plusSeconds(60));
    }

    MonitoringAuthority.Snapshot snapshot(UUID id, String role, boolean active) {
        return new MonitoringAuthority.Snapshot(
                id, role, attempt, publication, classroom, student, active, deadline, Instant.now());
    }

    void failure(int status, String code, org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(MonitoringFailure.class, operation);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }

    @Test
    void createRepeatsCurrentAuthorityAfterRoomNetworkCallBeforeTokenRelease() {
        var grant = service.create(actor, sessionId);
        assertEquals(grantId, grant.id());
        assertEquals("synthetic-sensitive-media-token", grant.token());
        var ordered = inOrder(authority, store, provider);
        ordered.verify(authority).check(actor, attempt, "STUDENT");
        ordered.verify(store).prepare(eq(actor), any(), eq(sessionId), any());
        ordered.verify(provider).ensureRoom(lease.room());
        ordered.verify(authority).check(actor, attempt, "STUDENT");
        ordered.verify(store).activate(eq(actor), any(), eq(grantId));
        ordered.verify(provider).token(lease.room(), lease.identity(), lease.role(), lease.leaseUntil());
        verify(sessions, times(2)).reconcile(eq(actor), any(), eq(sessionId), eq(true));
    }

    @Test
    void authorityLostDuringRoomCreationLeavesPreparedIntentWithoutToken() {
        when(authority.check(actor, attempt, "STUDENT"))
                .thenReturn(snapshot(student, "STUDENT", true))
                .thenThrow(new MonitoringFailure(503, "AUTHORITY_UNAVAILABLE"));
        failure(503, "AUTHORITY_UNAVAILABLE", () -> service.create(actor, sessionId));
        verify(provider).ensureRoom(lease.room());
        verify(store, never()).activate(any(), any(), any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void terminalAuthorityAfterRoomCreationCannotActivateOrReleaseToken() {
        var terminal = snapshot(student, "STUDENT", false);
        when(authority.check(actor, attempt, "STUDENT")).thenReturn(snapshot(student, "STUDENT", true), terminal);
        when(store.activate(actor, terminal, grantId)).thenThrow(new MonitoringFailure(409, "SESSION_ENDED"));
        failure(409, "SESSION_ENDED", () -> service.create(actor, sessionId));
        verify(sessions).reconcile(actor, terminal, sessionId, true);
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void initialResourceDenialNeverCreatesProviderRoom() {
        when(authority.check(actor, attempt, "STUDENT"))
                .thenThrow(new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.create(actor, sessionId));
        verifyNoInteractions(store, provider);
    }

    @Test
    void unrelatedStudentCannotAcquireTeacherGrant() {
        var other = actor(UUID.randomUUID(), Set.of("STUDENT"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.create(other, sessionId));
        verifyNoInteractions(authority, store, provider);
    }

    @Test
    void teacherRoleUsesCurrentAttemptAuthorityRatherThanClientRoleOrRoom() {
        var currentTeacher = actor(teacher, Set.of("STUDENT", "TEACHER"));
        when(authority.check(currentTeacher, attempt, "TEACHER"))
                .thenThrow(new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.create(currentTeacher, sessionId));
        verify(authority).check(currentTeacher, attempt, "TEACHER");
        verifyNoInteractions(store, provider);
    }

    @Test
    void unqualifiedProviderOrDisabledWorkerCannotPrepareGrant() {
        when(provider.qualified()).thenReturn(false);
        failure(503, "MEDIA_REVOCATION_UNVERIFIED", () -> service.create(actor, sessionId));
        when(provider.qualified()).thenReturn(true);
        properties.setWorkerEnabled(false);
        failure(503, "MEDIA_REVOCATION_UNVERIFIED", () -> service.renew(actor, sessionId, grantId));
        verifyNoInteractions(store);
        verify(provider, never()).ensureRoom(any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void renewalRequiresCurrentAuthorityAndOwnedGrantBinding() {
        when(store.find(grantId))
                .thenReturn(new MediaStore.Lease(
                        grantId,
                        UUID.randomUUID(),
                        student,
                        "STUDENT",
                        lease.room(),
                        lease.identity(),
                        lease.leaseUntil(),
                        "ACTIVE"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.renew(actor, sessionId, grantId));
        when(store.find(grantId))
                .thenReturn(new MediaStore.Lease(
                        grantId,
                        sessionId,
                        teacher,
                        "STUDENT",
                        lease.room(),
                        lease.identity(),
                        lease.leaseUntil(),
                        "ACTIVE"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.renew(actor, sessionId, grantId));
        verify(authority, times(2)).check(actor, attempt, "STUDENT");
        verify(store, never()).renew(any(), any(), any(), any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void expiredOrFencedLeaseCannotRenewEvenWithFreshUserAuthority() {
        when(store.renew(eq(actor), any(), eq(grantId), any()))
                .thenThrow(new MonitoringFailure(409, "MEDIA_GRANT_ENDED"));
        failure(409, "MEDIA_GRANT_ENDED", () -> service.renew(actor, sessionId, grantId));
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void renewalProviderOutageCannotExtendLeaseOrReleaseToken() {
        doThrow(new MonitoringFailure(503, "MEDIA_UNAVAILABLE")).when(provider).ensureRoom(lease.room());
        failure(503, "MEDIA_UNAVAILABLE", () -> service.renew(actor, sessionId, grantId));
        verify(store, never()).renew(any(), any(), any(), any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void renewalRepeatsCurrentAuthorityAfterProviderCallAndRejectsAuthorityLoss() {
        when(authority.check(actor, attempt, "STUDENT"))
                .thenReturn(snapshot(student, "STUDENT", true))
                .thenThrow(new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE"));
        failure(404, "PROCTORING_NOT_ACCESSIBLE", () -> service.renew(actor, sessionId, grantId));
        var ordered = inOrder(authority, provider);
        ordered.verify(authority).check(actor, attempt, "STUDENT");
        ordered.verify(provider).ensureRoom(lease.room());
        ordered.verify(authority).check(actor, attempt, "STUDENT");
        verify(store, never()).renew(any(), any(), any(), any());
        verify(provider, never()).token(any(), any(), any(), any());
    }

    @Test
    void revocationPersistsFenceWithoutDependingOnProviderAvailability() {
        var pending = new MediaStore.Lease(
                grantId,
                sessionId,
                student,
                "STUDENT",
                lease.room(),
                lease.identity(),
                lease.leaseUntil(),
                "REVOKE_PENDING");
        when(store.find(grantId)).thenReturn(lease, pending);
        assertEquals("REVOKE_PENDING", service.revoke(actor, sessionId, grantId).status());
        verify(store).fence(grantId, student);
        verifyNoInteractions(provider);
    }

    @Test
    void grantDiagnosticRepresentationNeverContainsCredentialMaterial() {
        var grant = service.create(actor, sessionId);
        assertEquals("Grant[REDACTED]", grant.toString());
        assertFalse(grant.toString().contains(grant.token()));
        assertFalse(grant.toString().contains(actor.userEvidence()));
    }
}

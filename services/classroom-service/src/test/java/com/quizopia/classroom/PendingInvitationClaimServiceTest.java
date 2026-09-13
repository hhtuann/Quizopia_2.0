package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.application.ClassroomMembershipRepository;
import com.quizopia.classroom.application.ClassroomRecordIdGenerator;
import com.quizopia.classroom.application.PendingClassroomInvitationRepository;
import com.quizopia.classroom.application.PendingInvitationClaimResult;
import com.quizopia.classroom.application.PendingInvitationClaimService;
import com.quizopia.classroom.domain.ClassroomMembership;
import com.quizopia.classroom.domain.NormalizedInvitationEmail;
import com.quizopia.classroom.domain.PendingClassroomInvitation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class PendingInvitationClaimServiceTest {
    private final InMemoryMembershipRepository memberships = new InMemoryMembershipRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final RecordingIdGenerator idGenerator = new RecordingIdGenerator();
    private PendingInvitationClaimService service;

    @BeforeEach
    void setUp() {
        service = new PendingInvitationClaimService(memberships, invitations, idGenerator);
    }

    @Test
    void singleClaimUsesVerifiedUserIdAndConsumesInvitation() {
        UUID classroomId = UUID.randomUUID();
        UUID verifiedUserId = UUID.randomUUID();
        UUID membershipId = idGenerator.addId();
        PendingClassroomInvitation invitation =
                invitation(classroomId, "Student.Name+Class@example.com", "Teacher-entered invitation name");

        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, " Student.Name+Class@EXAMPLE.COM ");

        assertEquals(new PendingInvitationClaimResult(1, 1), result);
        assertTrue(invitations
                .findByClassroomIdAndEmail(classroomId, invitation.email())
                .isEmpty());
        ClassroomMembership membership = memberships
                .findByClassroomIdAndUserId(classroomId, verifiedUserId)
                .orElseThrow();
        assertEquals(membershipId, membership.id());
        assertEquals(verifiedUserId, membership.userId());
        assertEquals(
                List.of("id", "classroomId", "userId"),
                java.util.Arrays.stream(ClassroomMembership.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
    }

    @Test
    void claimsEveryMatchingInvitationAcrossClassrooms() {
        UUID firstClassroom = UUID.randomUUID();
        UUID secondClassroom = UUID.randomUUID();
        UUID verifiedUserId = UUID.randomUUID();
        invitation(firstClassroom, "learner@example.com", "First invitation");
        invitation(secondClassroom, "learner@example.com", "Second invitation");
        PendingClassroomInvitation unrelated =
                invitation(UUID.randomUUID(), "other@example.com", "Unrelated invitation");
        idGenerator.addId();
        idGenerator.addId();

        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, "learner@EXAMPLE.COM");

        assertEquals(new PendingInvitationClaimResult(2, 2), result);
        assertTrue(memberships
                .findByClassroomIdAndUserId(firstClassroom, verifiedUserId)
                .isPresent());
        assertTrue(memberships
                .findByClassroomIdAndUserId(secondClassroom, verifiedUserId)
                .isPresent());
        assertEquals(List.of(unrelated), invitations.findAllByEmailForClaim(unrelated.email()));
    }

    @Test
    void localNormalizationPreservesLocalPartDotsAndPlusSuffix() {
        UUID verifiedUserId = UUID.randomUUID();
        PendingClassroomInvitation exact =
                invitation(UUID.randomUUID(), "First.Last+Class@example.com", "Exact local address");
        PendingClassroomInvitation localCase =
                invitation(UUID.randomUUID(), "first.Last+Class@example.com", "Different local case");
        PendingClassroomInvitation withoutDot =
                invitation(UUID.randomUUID(), "FirstLast+Class@example.com", "Dot removed");
        PendingClassroomInvitation withoutPlus =
                invitation(UUID.randomUUID(), "First.Last@example.com", "Plus suffix removed");
        idGenerator.addId();

        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, " First.Last+Class@EXAMPLE.COM ");

        assertEquals(new PendingInvitationClaimResult(1, 1), result);
        assertTrue(invitations
                .findByClassroomIdAndEmail(exact.classroomId(), exact.email())
                .isEmpty());
        assertEquals(List.of(localCase), invitations.findAllByEmailForClaim(localCase.email()));
        assertEquals(List.of(withoutDot), invitations.findAllByEmailForClaim(withoutDot.email()));
        assertEquals(List.of(withoutPlus), invitations.findAllByEmailForClaim(withoutPlus.email()));
    }

    @Test
    void noMatchingInvitationIsSuccessfulNoOp() {
        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(UUID.randomUUID(), "nobody@example.com");

        assertEquals(new PendingInvitationClaimResult(0, 0), result);
        assertEquals(0, idGenerator.calls);
        assertTrue(memberships.values.isEmpty());
    }

    @Test
    void repeatedClaimDoesNotCreateDuplicateMembership() {
        UUID classroomId = UUID.randomUUID();
        UUID verifiedUserId = UUID.randomUUID();
        invitation(classroomId, "student@example.com", "Student");
        idGenerator.addId();

        assertEquals(
                new PendingInvitationClaimResult(1, 1),
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, "student@example.com"));
        assertEquals(
                new PendingInvitationClaimResult(0, 0),
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, "student@example.com"));
        assertEquals(1, memberships.values.size());
        assertEquals(1, idGenerator.calls);
    }

    @Test
    void existingMembershipSatisfiesClaimAndInvitationIsConsumed() {
        UUID classroomId = UUID.randomUUID();
        UUID verifiedUserId = UUID.randomUUID();
        ClassroomMembership existing = new ClassroomMembership(UUID.randomUUID(), classroomId, verifiedUserId);
        memberships.insert(existing);
        PendingClassroomInvitation invitation = invitation(classroomId, "student@example.com", "Student");

        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, "student@example.com");

        assertEquals(new PendingInvitationClaimResult(1, 0), result);
        assertEquals(List.of(existing), new ArrayList<>(memberships.values.values()));
        assertTrue(invitations
                .findByClassroomIdAndEmail(classroomId, invitation.email())
                .isEmpty());
        assertEquals(0, idGenerator.calls);
    }

    @Test
    void existingMembershipWithoutInvitationIsSuccessfulNoOp() {
        UUID classroomId = UUID.randomUUID();
        UUID verifiedUserId = UUID.randomUUID();
        memberships.insert(new ClassroomMembership(UUID.randomUUID(), classroomId, verifiedUserId));

        PendingInvitationClaimResult result =
                service.claimPendingInvitationsForVerifiedUser(verifiedUserId, "student@example.com");

        assertEquals(new PendingInvitationClaimResult(0, 0), result);
        assertEquals(1, memberships.values.size());
        assertEquals(0, idGenerator.calls);
    }

    @Test
    void rejectsMissingUserIdAndInvalidEmailWithLocalValueSemantics() {
        assertThrows(
                NullPointerException.class,
                () -> service.claimPendingInvitationsForVerifiedUser(null, "student@example.com"));
        assertThrows(
                NullPointerException.class,
                () -> service.claimPendingInvitationsForVerifiedUser(UUID.randomUUID(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.claimPendingInvitationsForVerifiedUser(UUID.randomUUID(), "not-an-email"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.claimPendingInvitationsForVerifiedUser(UUID.randomUUID(), "two words@example.com"));
        assertTrue(memberships.values.isEmpty());
        assertTrue(invitations.values.isEmpty());
    }

    @Test
    void useCaseIsOneTransactionalOperationWithOnlyTrustedFactInputs() throws NoSuchMethodException {
        Method claim = PendingInvitationClaimService.class.getMethod(
                "claimPendingInvitationsForVerifiedUser", UUID.class, String.class);

        assertNotNull(claim.getAnnotation(Transactional.class));
        assertEquals(PendingInvitationClaimResult.class, claim.getReturnType());
        assertArrayEquals(new Class<?>[] {UUID.class, String.class}, claim.getParameterTypes());
    }

    private PendingClassroomInvitation invitation(UUID classroomId, String email, String invitedFullName) {
        PendingClassroomInvitation invitation = new PendingClassroomInvitation(
                UUID.randomUUID(), classroomId, new NormalizedInvitationEmail(email), invitedFullName);
        invitations.insert(invitation);
        return invitation;
    }

    private record MembershipKey(UUID classroomId, UUID userId) {}

    private static final class InMemoryMembershipRepository implements ClassroomMembershipRepository {
        private final Map<MembershipKey, ClassroomMembership> values = new LinkedHashMap<>();

        @Override
        public void insert(ClassroomMembership membership) {
            if (!insertIfAbsent(membership)) {
                throw new IllegalStateException("duplicate membership");
            }
        }

        @Override
        public boolean insertIfAbsent(ClassroomMembership membership) {
            MembershipKey key = new MembershipKey(membership.classroomId(), membership.userId());
            return values.putIfAbsent(key, membership) == null;
        }

        @Override
        public Optional<ClassroomMembership> findByClassroomIdAndUserId(UUID classroomId, UUID userId) {
            return Optional.ofNullable(values.get(new MembershipKey(classroomId, userId)));
        }
    }

    private static final class InMemoryInvitationRepository implements PendingClassroomInvitationRepository {
        private final Map<UUID, PendingClassroomInvitation> values = new LinkedHashMap<>();

        @Override
        public void insert(PendingClassroomInvitation invitation) {
            values.put(invitation.id(), invitation);
        }

        @Override
        public Optional<PendingClassroomInvitation> findByClassroomIdAndEmail(
                UUID classroomId, NormalizedInvitationEmail email) {
            return values.values().stream()
                    .filter(invitation -> invitation.classroomId().equals(classroomId))
                    .filter(invitation -> invitation.email().equals(email))
                    .findFirst();
        }

        @Override
        public List<PendingClassroomInvitation> findAllByEmailForClaim(NormalizedInvitationEmail email) {
            return values.values().stream()
                    .filter(invitation -> invitation.email().equals(email))
                    .toList();
        }

        @Override
        public void deleteById(UUID id) {
            if (values.remove(id) == null) {
                throw new IllegalStateException("missing invitation");
            }
        }
    }

    private static final class RecordingIdGenerator implements ClassroomRecordIdGenerator {
        private final Deque<UUID> ids = new ArrayDeque<>();
        private int calls;

        UUID addId() {
            UUID id = UUID.randomUUID();
            ids.addLast(id);
            return id;
        }

        @Override
        public UUID generate() {
            calls++;
            return ids.removeFirst();
        }
    }
}

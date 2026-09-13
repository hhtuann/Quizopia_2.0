package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.application.ClassroomApplicationService;
import com.quizopia.classroom.application.ClassroomMembershipRepository;
import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.application.ClassroomOwnershipDeniedException;
import com.quizopia.classroom.application.ClassroomRecordIdGenerator;
import com.quizopia.classroom.application.ClassroomRepository;
import com.quizopia.classroom.application.ManualStudentAdditionInput;
import com.quizopia.classroom.application.ManualStudentAdditionResult;
import com.quizopia.classroom.application.ManualStudentAdditionService;
import com.quizopia.classroom.application.ManualStudentAdditionTransaction;
import com.quizopia.classroom.application.PendingClassroomInvitationRepository;
import com.quizopia.classroom.application.VerifiedUserLookup;
import com.quizopia.classroom.domain.Classroom;
import com.quizopia.classroom.domain.ClassroomMembership;
import com.quizopia.classroom.domain.NormalizedInvitationEmail;
import com.quizopia.classroom.domain.PendingClassroomInvitation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ManualStudentAdditionServiceTest {
    private final RecordingClassroomRepository classrooms = new RecordingClassroomRepository();
    private final RecordingMembershipRepository memberships = new RecordingMembershipRepository();
    private final RecordingInvitationRepository invitations = new RecordingInvitationRepository();
    private final RecordingIdGenerator idGenerator = new RecordingIdGenerator();
    private ClassroomApplicationService classroomService;
    private ManualStudentAdditionTransaction transaction;

    @BeforeEach
    void setUp() {
        classroomService = new ClassroomApplicationService(classrooms, idGenerator);
        transaction = new ManualStudentAdditionTransaction(classroomService, memberships, invitations, idGenerator);
    }

    @Test
    void verifiedUserCreatesMembershipForLookupUserOnly() {
        UUID owner = UUID.randomUUID();
        Classroom classroom = ownedClassroom(owner);
        UUID verifiedUserId = UUID.randomUUID();
        UUID membershipId = idGenerator.addId();
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(
                Optional.of(verifiedUserId), () -> assertEquals(1, classrooms.findCount));
        ManualStudentAdditionInput input =
                new ManualStudentAdditionInput("Teacher-entered name", "Known.User+School@EXAMPLE.COM");

        ManualStudentAdditionResult result = service(lookup).addStudent(owner, classroom.id(), input);

        assertEquals(ManualStudentAdditionResult.Outcome.MEMBERSHIP_ESTABLISHED, result.outcome());
        assertEquals(membershipId, result.createdRecordId());
        assertEquals("Known.User+School@EXAMPLE.COM", lookup.lastSuppliedEmail);
        assertEquals(1, lookup.calls);
        assertEquals(2, classrooms.findCount);
        assertEquals(1, memberships.insertCount);
        assertEquals(membershipId, memberships.lastInserted.id());
        assertEquals(classroom.id(), memberships.lastInserted.classroomId());
        assertEquals(verifiedUserId, memberships.lastInserted.userId());
        assertEquals(0, invitations.insertCount);
    }

    @Test
    void manualInputCannotSupplyOwnerOrMembershipUserId() {
        assertEquals(
                List.of("invitedFullName", "email"),
                Arrays.stream(ManualStudentAdditionInput.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
    }

    @Test
    void missingVerifiedUserCreatesPendingInvitationWithLocalNormalization() {
        UUID owner = UUID.randomUUID();
        Classroom classroom = ownedClassroom(owner);
        UUID invitationId = idGenerator.addId();
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(Optional.empty(), () -> {});
        ManualStudentAdditionInput input =
                new ManualStudentAdditionInput("Invited learner", " First.Last+Class@EXAMPLE.COM ");

        ManualStudentAdditionResult result = service(lookup).addStudent(owner, classroom.id(), input);

        assertEquals(ManualStudentAdditionResult.Outcome.PENDING_INVITATION_CREATED, result.outcome());
        assertEquals(invitationId, result.createdRecordId());
        assertEquals(" First.Last+Class@EXAMPLE.COM ", lookup.lastSuppliedEmail);
        assertEquals(0, memberships.insertCount);
        assertEquals(1, invitations.insertCount);
        assertEquals(invitationId, invitations.lastInserted.id());
        assertEquals(classroom.id(), invitations.lastInserted.classroomId());
        assertEquals(
                "First.Last+Class@example.com", invitations.lastInserted.email().value());
        assertEquals("Invited learner", invitations.lastInserted.invitedFullName());
        assertEquals(1, idGenerator.calls);
    }

    @Test
    void missingClassroomStopsBeforeVerifiedUserLookup() {
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(Optional.of(UUID.randomUUID()), () -> {});

        assertThrows(ClassroomNotFoundException.class, () -> service(lookup)
                .addStudent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new ManualStudentAdditionInput("Name", "student@example.com")));

        assertNoLookupOrWrite(lookup);
    }

    @Test
    void nonOwnerStopsBeforeVerifiedUserLookup() {
        Classroom classroom = ownedClassroom(UUID.randomUUID());
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(Optional.of(UUID.randomUUID()), () -> {});

        assertThrows(ClassroomOwnershipDeniedException.class, () -> service(lookup)
                .addStudent(
                        UUID.randomUUID(),
                        classroom.id(),
                        new ManualStudentAdditionInput("Name", "student@example.com")));

        assertNoLookupOrWrite(lookup);
    }

    @Test
    void ownershipIsRevalidatedAfterLookupBeforeMutation() {
        UUID originalOwner = UUID.randomUUID();
        Classroom classroom = ownedClassroom(originalOwner);
        UUID userId = UUID.randomUUID();
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(
                Optional.of(userId),
                () -> classrooms.put(new Classroom(
                        classroom.id(),
                        UUID.randomUUID(),
                        classroom.name(),
                        classroom.subject(),
                        classroom.grade(),
                        null)));

        assertThrows(ClassroomOwnershipDeniedException.class, () -> service(lookup)
                .addStudent(
                        originalOwner, classroom.id(), new ManualStudentAdditionInput("Name", "student@example.com")));

        assertEquals(1, lookup.calls);
        assertEquals(2, classrooms.findCount);
        assertEquals(0, memberships.insertCount);
        assertEquals(0, invitations.insertCount);
        assertEquals(0, idGenerator.calls);
    }

    @Test
    void requiredInputAndEmailShapeFailWithoutLookupOrWrite() {
        assertThrows(NullPointerException.class, () -> new ManualStudentAdditionInput(null, "a@example.com"));
        assertThrows(IllegalArgumentException.class, () -> new ManualStudentAdditionInput(" ", "a@example.com"));
        assertThrows(NullPointerException.class, () -> new ManualStudentAdditionInput("Name", null));
        assertThrows(IllegalArgumentException.class, () -> new ManualStudentAdditionInput("Name", "\t"));

        UUID owner = UUID.randomUUID();
        Classroom classroom = ownedClassroom(owner);
        RecordingVerifiedUserLookup lookup = new RecordingVerifiedUserLookup(Optional.empty(), () -> {});
        ManualStudentAdditionInput invalidEmail = new ManualStudentAdditionInput("Name", "not-an-email");

        assertThrows(
                IllegalArgumentException.class, () -> service(lookup).addStudent(owner, classroom.id(), invalidEmail));
        assertFalse(invalidEmail.toString().contains(invalidEmail.email()));
        assertNoLookupOrWrite(lookup);
    }

    @Test
    void coordinatorHasNoTransactionWhileMutationsAreTransactional() throws NoSuchMethodException {
        Method coordinator = ManualStudentAdditionService.class.getMethod(
                "addStudent", UUID.class, UUID.class, ManualStudentAdditionInput.class);
        Method membershipMutation = ManualStudentAdditionTransaction.class.getMethod(
                "establishMembership", UUID.class, UUID.class, UUID.class);
        Method invitationMutation = ManualStudentAdditionTransaction.class.getMethod(
                "createPendingInvitation", UUID.class, UUID.class, NormalizedInvitationEmail.class, String.class);

        assertNull(ManualStudentAdditionService.class.getAnnotation(Transactional.class));
        assertNull(coordinator.getAnnotation(Transactional.class));
        assertNotNull(membershipMutation.getAnnotation(Transactional.class));
        assertNotNull(invitationMutation.getAnnotation(Transactional.class));
    }

    private ManualStudentAdditionService service(VerifiedUserLookup lookup) {
        return new ManualStudentAdditionService(classroomService, lookup, transaction);
    }

    private Classroom ownedClassroom(UUID owner) {
        Classroom classroom = new Classroom(UUID.randomUUID(), owner, "Class", "Subject", "Grade", null);
        classrooms.put(classroom);
        return classroom;
    }

    private void assertNoLookupOrWrite(RecordingVerifiedUserLookup lookup) {
        assertEquals(0, lookup.calls);
        assertEquals(0, memberships.insertCount);
        assertEquals(0, invitations.insertCount);
        assertEquals(0, idGenerator.calls);
    }

    private static final class RecordingClassroomRepository implements ClassroomRepository {
        private final Map<UUID, Classroom> values = new LinkedHashMap<>();
        private int findCount;

        void put(Classroom classroom) {
            values.put(classroom.id(), classroom);
        }

        @Override
        public void insert(Classroom classroom) {
            put(classroom);
        }

        @Override
        public Optional<Classroom> findById(UUID id) {
            findCount++;
            return Optional.ofNullable(values.get(id));
        }
    }

    private static final class RecordingMembershipRepository implements ClassroomMembershipRepository {
        private ClassroomMembership lastInserted;
        private int insertCount;

        @Override
        public void insert(ClassroomMembership membership) {
            lastInserted = membership;
            insertCount++;
        }

        @Override
        public boolean insertIfAbsent(ClassroomMembership membership) {
            if (findByClassroomIdAndUserId(membership.classroomId(), membership.userId())
                    .isPresent()) {
                return false;
            }
            insert(membership);
            return true;
        }

        @Override
        public Optional<ClassroomMembership> findByClassroomIdAndUserId(UUID classroomId, UUID userId) {
            if (lastInserted != null
                    && lastInserted.classroomId().equals(classroomId)
                    && lastInserted.userId().equals(userId)) {
                return Optional.of(lastInserted);
            }
            return Optional.empty();
        }
    }

    private static final class RecordingInvitationRepository implements PendingClassroomInvitationRepository {
        private PendingClassroomInvitation lastInserted;
        private int insertCount;

        @Override
        public void insert(PendingClassroomInvitation invitation) {
            lastInserted = invitation;
            insertCount++;
        }

        @Override
        public Optional<PendingClassroomInvitation> findByClassroomIdAndEmail(
                UUID classroomId, NormalizedInvitationEmail email) {
            if (lastInserted != null
                    && lastInserted.classroomId().equals(classroomId)
                    && lastInserted.email().equals(email)) {
                return Optional.of(lastInserted);
            }
            return Optional.empty();
        }

        @Override
        public List<PendingClassroomInvitation> findAllByEmailForClaim(NormalizedInvitationEmail email) {
            return lastInserted != null && lastInserted.email().equals(email) ? List.of(lastInserted) : List.of();
        }

        @Override
        public void deleteById(UUID id) {
            if (lastInserted != null && lastInserted.id().equals(id)) {
                lastInserted = null;
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

    private static final class RecordingVerifiedUserLookup implements VerifiedUserLookup {
        private final Optional<UUID> result;
        private final Runnable onLookup;
        private int calls;
        private String lastSuppliedEmail;

        RecordingVerifiedUserLookup(Optional<UUID> result, Runnable onLookup) {
            this.result = result;
            this.onLookup = onLookup;
        }

        @Override
        public Optional<UUID> findVerifiedUserIdByEmail(String suppliedEmail) {
            calls++;
            lastSuppliedEmail = suppliedEmail;
            onLookup.run();
            return result;
        }
    }
}

package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.application.*;
import com.quizopia.classroom.domain.*;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("persistence-test")
class ClassroomPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("classroom_db")
            .withUsername("classroom")
            .withPassword("classroom_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    ClassroomRepository classrooms;

    @Autowired
    ClassroomMembershipRepository memberships;

    @Autowired
    PendingClassroomInvitationRepository invitations;

    @Autowired
    ClassroomApplicationService classroomApplicationService;

    @Autowired
    ManualStudentAdditionTransaction manualStudentAdditionTransaction;

    @Autowired
    PendingInvitationClaimService pendingInvitationClaimService;

    @Autowired
    Flyway flyway;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    JdbcTemplate jdbc;

    private Classroom classroom() {
        var value = new Classroom(UUID.randomUUID(), UUID.randomUUID(), "Class", "Any subject", "Mixed grade", null);
        classrooms.insert(value);
        return value;
    }

    @Test
    void migrationAndHibernateValidation() {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        flyway.validate();
        assertTrue(emf.isOpen());
        assertEquals(3, emf.getMetamodel().getEntities().size());
    }

    @Test
    void classroomRoundTrip() {
        var value = classroom();
        assertEquals(value, classrooms.findById(value.id()).orElseThrow());
        assertTrue(classrooms.findById(UUID.randomUUID()).isEmpty());
        var described = new Classroom(UUID.randomUUID(), UUID.randomUUID(), "N", "S", "G", "Description");
        classrooms.insert(described);
        assertEquals(described, classrooms.findById(described.id()).orElseThrow());
    }

    @Test
    void applicationServiceCreatesAndRetrievesOwnedClassroom() {
        UUID callerUserId = UUID.randomUUID();
        Classroom created = classroomApplicationService.create(
                callerUserId, new CreateClassroomInput("Owned class", "Flexible subject", "Flexible grade", null));

        assertNotNull(created.id());
        assertEquals(callerUserId, created.ownerTeacherUserId());
        assertEquals(created, classroomApplicationService.getOwnedClassroom(callerUserId, created.id()));
        assertEquals(created, classrooms.findById(created.id()).orElseThrow());
    }

    @Test
    void manualAdditionPersistsVerifiedUserMembershipOutsideLookupTransaction() {
        UUID owner = UUID.randomUUID();
        Classroom classroom = classroomApplicationService.create(
                owner, new CreateClassroomInput("Owned class", "Subject", "Grade", null));
        UUID verifiedUserId = UUID.randomUUID();
        VerifiedUserLookup lookup = suppliedEmail -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("Known.User+Class@EXAMPLE.COM", suppliedEmail);
            return Optional.of(verifiedUserId);
        };
        ManualStudentAdditionService service =
                new ManualStudentAdditionService(classroomApplicationService, lookup, manualStudentAdditionTransaction);

        ManualStudentAdditionResult result = service.addStudent(
                owner,
                classroom.id(),
                new ManualStudentAdditionInput("Teacher metadata", "Known.User+Class@EXAMPLE.COM"));

        assertEquals(ManualStudentAdditionResult.Outcome.MEMBERSHIP_ESTABLISHED, result.outcome());
        ClassroomMembership membership = memberships
                .findByClassroomIdAndUserId(classroom.id(), verifiedUserId)
                .orElseThrow();
        assertEquals(result.createdRecordId(), membership.id());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from pending_classroom_invitations where classroom_id = ?",
                        Integer.class,
                        classroom.id()));
    }

    @Test
    void manualAdditionPersistsPendingInvitationWhenVerifiedUserIsMissing() {
        UUID owner = UUID.randomUUID();
        Classroom classroom = classroomApplicationService.create(
                owner, new CreateClassroomInput("Owned class", "Subject", "Grade", null));
        VerifiedUserLookup lookup = suppliedEmail -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(" First.Last+Class@EXAMPLE.COM ", suppliedEmail);
            return Optional.empty();
        };
        ManualStudentAdditionService service =
                new ManualStudentAdditionService(classroomApplicationService, lookup, manualStudentAdditionTransaction);

        ManualStudentAdditionResult result = service.addStudent(
                owner,
                classroom.id(),
                new ManualStudentAdditionInput("Invited learner", " First.Last+Class@EXAMPLE.COM "));

        assertEquals(ManualStudentAdditionResult.Outcome.PENDING_INVITATION_CREATED, result.outcome());
        PendingClassroomInvitation invitation = invitations
                .findByClassroomIdAndEmail(
                        classroom.id(), new NormalizedInvitationEmail("First.Last+Class@example.com"))
                .orElseThrow();
        assertEquals(result.createdRecordId(), invitation.id());
        assertEquals("Invited learner", invitation.invitedFullName());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from classroom_memberships where classroom_id = ?",
                        Integer.class,
                        classroom.id()));
    }

    @Test
    void membershipRoundTrip() {
        var value = new ClassroomMembership(UUID.randomUUID(), classroom().id(), UUID.randomUUID());
        memberships.insert(value);
        assertEquals(
                value,
                memberships
                        .findByClassroomIdAndUserId(value.classroomId(), value.userId())
                        .orElseThrow());
        assertTrue(memberships
                .findByClassroomIdAndUserId(value.classroomId(), UUID.randomUUID())
                .isEmpty());
    }

    @Test
    void invitationRoundTrip() {
        var value = new PendingClassroomInvitation(
                UUID.randomUUID(),
                classroom().id(),
                new NormalizedInvitationEmail(" First.Last+Class@GMAIL.COM "),
                "Invited name");
        invitations.insert(value);
        assertEquals(
                value,
                invitations
                        .findByClassroomIdAndEmail(
                                value.classroomId(), new NormalizedInvitationEmail("First.Last+Class@gmail.com"))
                        .orElseThrow());
        assertTrue(invitations
                .findByClassroomIdAndEmail(UUID.randomUUID(), value.email())
                .isEmpty());
    }

    @Test
    void claimRepositoryOperationsQueryByNormalizedEmailAndDeleteInvitation() {
        NormalizedInvitationEmail matchingEmail = new NormalizedInvitationEmail("Student.Name+Class@example.com");
        PendingClassroomInvitation first =
                new PendingClassroomInvitation(UUID.randomUUID(), classroom().id(), matchingEmail, "First invitation");
        PendingClassroomInvitation second =
                new PendingClassroomInvitation(UUID.randomUUID(), classroom().id(), matchingEmail, "Second invitation");
        PendingClassroomInvitation unrelated = new PendingClassroomInvitation(
                UUID.randomUUID(),
                classroom().id(),
                new NormalizedInvitationEmail("other@example.com"),
                "Unrelated invitation");
        invitations.insert(first);
        invitations.insert(second);
        invitations.insert(unrelated);

        assertEquals(
                Set.of(first, second),
                new HashSet<>(invitations.findAllByEmailForClaim(
                        new NormalizedInvitationEmail(" Student.Name+Class@EXAMPLE.COM "))));
        invitations.deleteById(first.id());

        assertTrue(invitations
                .findByClassroomIdAndEmail(first.classroomId(), first.email())
                .isEmpty());
        assertEquals(List.of(second), invitations.findAllByEmailForClaim(matchingEmail));
        assertTrue(invitations
                .findByClassroomIdAndEmail(unrelated.classroomId(), unrelated.email())
                .isPresent());
    }

    @Test
    void membershipInsertIfAbsentUsesUniqueClassroomUserPair() {
        UUID classroomId = classroom().id();
        UUID verifiedUserId = UUID.randomUUID();

        assertTrue(memberships.insertIfAbsent(new ClassroomMembership(UUID.randomUUID(), classroomId, verifiedUserId)));
        assertFalse(
                memberships.insertIfAbsent(new ClassroomMembership(UUID.randomUUID(), classroomId, verifiedUserId)));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from classroom_memberships where classroom_id = ? and user_id = ?",
                        Integer.class,
                        classroomId,
                        verifiedUserId));
    }

    @Test
    void claimTransactionPersistsMembershipsAcrossAllMatchingClassroomsAndConsumesInvitations() {
        UUID verifiedUserId = UUID.randomUUID();
        Classroom firstClassroom = classroom();
        Classroom secondClassroom = classroom();
        Classroom unrelatedClassroom = classroom();
        NormalizedInvitationEmail matchingEmail = new NormalizedInvitationEmail("First.Last+Class@example.com");
        invitations.insert(new PendingClassroomInvitation(
                UUID.randomUUID(), firstClassroom.id(), matchingEmail, "First metadata"));
        invitations.insert(new PendingClassroomInvitation(
                UUID.randomUUID(), secondClassroom.id(), matchingEmail, "Second metadata"));
        PendingClassroomInvitation unrelated = new PendingClassroomInvitation(
                UUID.randomUUID(),
                unrelatedClassroom.id(),
                new NormalizedInvitationEmail("first.Last+Class@example.com"),
                "Unrelated local-part case");
        invitations.insert(unrelated);

        PendingInvitationClaimResult result = pendingInvitationClaimService.claimPendingInvitationsForVerifiedUser(
                verifiedUserId, " First.Last+Class@EXAMPLE.COM ");

        assertEquals(new PendingInvitationClaimResult(2, 2), result);
        assertEquals(
                verifiedUserId,
                memberships
                        .findByClassroomIdAndUserId(firstClassroom.id(), verifiedUserId)
                        .orElseThrow()
                        .userId());
        assertEquals(
                verifiedUserId,
                memberships
                        .findByClassroomIdAndUserId(secondClassroom.id(), verifiedUserId)
                        .orElseThrow()
                        .userId());
        assertTrue(invitations.findAllByEmailForClaim(matchingEmail).isEmpty());
        assertTrue(invitations
                .findByClassroomIdAndEmail(unrelated.classroomId(), unrelated.email())
                .isPresent());
    }

    @Test
    void claimConsumesInvitationForExistingMembershipAndRepeatedClaimIsStable() {
        UUID classroomId = classroom().id();
        UUID verifiedUserId = UUID.randomUUID();
        NormalizedInvitationEmail email = new NormalizedInvitationEmail("student@example.com");
        memberships.insert(new ClassroomMembership(UUID.randomUUID(), classroomId, verifiedUserId));
        invitations.insert(new PendingClassroomInvitation(UUID.randomUUID(), classroomId, email, "Metadata only"));

        assertEquals(
                new PendingInvitationClaimResult(1, 0),
                pendingInvitationClaimService.claimPendingInvitationsForVerifiedUser(
                        verifiedUserId, "student@EXAMPLE.COM"));
        assertTrue(invitations.findByClassroomIdAndEmail(classroomId, email).isEmpty());
        assertEquals(
                new PendingInvitationClaimResult(0, 0),
                pendingInvitationClaimService.claimPendingInvitationsForVerifiedUser(
                        verifiedUserId, "student@example.com"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from classroom_memberships where classroom_id = ? and user_id = ?",
                        Integer.class,
                        classroomId,
                        verifiedUserId));
    }

    @Test
    void uniqueMembershipPerClassroom() {
        UUID classroomId = classroom().id(), user = UUID.randomUUID();
        memberships.insert(new ClassroomMembership(UUID.randomUUID(), classroomId, user));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> memberships.insert(new ClassroomMembership(UUID.randomUUID(), classroomId, user)));
        memberships.insert(
                new ClassroomMembership(UUID.randomUUID(), classroom().id(), user));
    }

    @Test
    void uniqueInvitationPerClassroom() {
        UUID classroomId = classroom().id();
        var email = new NormalizedInvitationEmail("a@example.com");
        invitations.insert(new PendingClassroomInvitation(UUID.randomUUID(), classroomId, email, "Name"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> invitations.insert(new PendingClassroomInvitation(
                        UUID.randomUUID(), classroomId, new NormalizedInvitationEmail(" a@EXAMPLE.COM "), "Other")));
        invitations.insert(
                new PendingClassroomInvitation(UUID.randomUUID(), classroom().id(), email, "Name"));
    }

    @Test
    void membershipForeignKey() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> memberships.insert(
                        new ClassroomMembership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void invitationForeignKey() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> invitations.insert(new PendingClassroomInvitation(
                        UUID.randomUUID(), UUID.randomUUID(), new NormalizedInvitationEmail("a@example.com"), "Name")));
    }

    @Test
    void duplicateIdCannotOverwriteClassroom() {
        var value = classroom();
        assertThrows(
                DataIntegrityViolationException.class,
                () -> classrooms.insert(
                        new Classroom(value.id(), value.ownerTeacherUserId(), "Changed", "S", "G", null)));
        assertEquals(value, classrooms.findById(value.id()).orElseThrow());
    }

    @Test
    void databaseEnforcesRequiredMetadata() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "insert into classrooms (id,owner_teacher_user_id,name,subject,grade) values (?,?,?,?,?)",
                        UUID.randomUUID(),
                        null,
                        "N",
                        "S",
                        "G"));
        for (String column : new String[] {"name", "subject", "grade"}) {
            UUID id = classroom().id();
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbc.update("update classrooms set " + column + " = ? where id = ?", " \t\n", id));
        }
    }

    @Test
    void databaseEnforcesInvitationText() {
        UUID classroomId = classroom().id();
        for (String email : new String[] {"a@EXAMPLE.COM", " a@example.com", "a b@example.com", "invalid"}) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> jdbc.update(
                            "insert into pending_classroom_invitations values (?,?,?,?)",
                            UUID.randomUUID(),
                            classroomId,
                            email,
                            "Name"));
        }
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "insert into pending_classroom_invitations values (?,?,?,?)",
                        UUID.randomUUID(),
                        classroomId,
                        "a@example.com",
                        " \t"));
    }

    @Test
    void externalUserReferencesRequireNoIdentityTables() {
        var value = classroom();
        UUID user = UUID.randomUUID();
        memberships.insert(new ClassroomMembership(UUID.randomUUID(), value.id(), user));
        assertEquals(
                user,
                memberships
                        .findByClassroomIdAndUserId(value.id(), user)
                        .orElseThrow()
                        .userId());
        assertEquals(
                List.of("classrooms", "classrooms"),
                jdbc.queryForList(
                        "select confrelid::regclass::text from pg_constraint where contype = 'f' and connamespace = 'public'::regnamespace order by conname",
                        String.class));
        assertEquals(
                Set.of("classrooms", "classroom_memberships", "pending_classroom_invitations", "flyway_schema_history"),
                new HashSet<>(jdbc.queryForList(
                        "select tablename from pg_tables where schemaname = 'public'", String.class)));
    }
}

package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.domain.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassroomDomainTest {
    private final UUID id = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void classroomPreservesTaxonomyNeutralMetadata() {
        var value = new Classroom(id, user, "Group", "Interdisciplinary / future subject", "Mixed ages", null);
        assertEquals(id, value.id());
        assertEquals(user, value.ownerTeacherUserId());
        assertEquals("Interdisciplinary / future subject", value.subject());
        assertEquals("Mixed ages", value.grade());
        assertNull(value.description());
        assertEquals("Notes", new Classroom(id, user, "N", "S", "G", "Notes").description());
    }

    @Test
    void requiredClassroomIdentifiers() {
        assertThrows(NullPointerException.class, () -> new Classroom(null, user, "N", "S", "G", null));
        assertThrows(NullPointerException.class, () -> new Classroom(id, null, "N", "S", "G", null));
    }

    @Test
    void ownershipUsesInternalUserIdValueEquality() {
        UUID equalValue = UUID.fromString(user.toString());
        Classroom classroom = new Classroom(id, user, "N", "S", "G", null);

        assertNotSame(user, equalValue);
        assertTrue(classroom.isOwnedBy(equalValue));
        assertFalse(classroom.isOwnedBy(UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> classroom.isOwnedBy(null));
    }

    @Test
    void requiredClassroomMetadata() {
        for (String bad : new String[] {"", " \t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new Classroom(id, user, bad, "S", "G", null));
            assertThrows(IllegalArgumentException.class, () -> new Classroom(id, user, "N", bad, "G", null));
            assertThrows(IllegalArgumentException.class, () -> new Classroom(id, user, "N", "S", bad, null));
        }
        assertThrows(NullPointerException.class, () -> new Classroom(id, user, null, "S", "G", null));
        assertThrows(NullPointerException.class, () -> new Classroom(id, user, "N", null, "G", null));
        assertThrows(NullPointerException.class, () -> new Classroom(id, user, "N", "S", null, null));
    }

    @Test
    void conservativeEmailNormalization() {
        var email = new NormalizedInvitationEmail(" First.Last+School@GMAIL.COM ");
        assertEquals("First.Last+School@gmail.com", email.value());
        assertEquals(email, new NormalizedInvitationEmail(email.value()));
        assertNotEquals(email, new NormalizedInvitationEmail("first.last+School@gmail.com"));
        assertEquals("a@school.example", new NormalizedInvitationEmail("a@SCHOOL.EXAMPLE").value());
        assertFalse(email.toString().contains(email.value()));
    }

    @Test
    void invalidEmail() {
        assertThrows(NullPointerException.class, () -> new NormalizedInvitationEmail(null));
        for (String bad : new String[] {
            "", " ", "missing", "@domain", "local@", "a@@b", "a b@c", "a@b\n.c", "a".repeat(319) + "@b"
        }) assertThrows(IllegalArgumentException.class, () -> new NormalizedInvitationEmail(bad));
    }

    @Test
    void invitationRequiresIdentifiersEmailAndName() {
        var email = new NormalizedInvitationEmail("a@example.com");
        assertThrows(NullPointerException.class, () -> new PendingClassroomInvitation(null, id, email, "Name"));
        assertThrows(NullPointerException.class, () -> new PendingClassroomInvitation(user, null, email, "Name"));
        assertThrows(NullPointerException.class, () -> new PendingClassroomInvitation(user, id, null, "Name"));
        assertThrows(NullPointerException.class, () -> new PendingClassroomInvitation(user, id, email, null));
        assertThrows(IllegalArgumentException.class, () -> new PendingClassroomInvitation(user, id, email, " \t"));
        var value = new PendingClassroomInvitation(user, id, email, "Teacher-entered name");
        assertEquals("Teacher-entered name", value.invitedFullName());
        assertFalse(value.toString().contains(value.invitedFullName()));
    }

    @Test
    void membershipIdentity() {
        UUID membershipId = UUID.randomUUID();
        var value = new ClassroomMembership(membershipId, id, user);
        assertEquals(new ClassroomMembership(membershipId, id, user), value);
        assertNotEquals(new ClassroomMembership(UUID.randomUUID(), id, user), value);
        assertThrows(NullPointerException.class, () -> new ClassroomMembership(null, id, user));
        assertThrows(NullPointerException.class, () -> new ClassroomMembership(membershipId, null, user));
        assertThrows(NullPointerException.class, () -> new ClassroomMembership(membershipId, id, null));
    }
}

package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.classroom.application.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassroomDirectoryTest {
    @Test
    void cursorAuthenticatesOwnerPositionAndCredentialAcrossInstances() {
        var codec = new ClassroomDirectoryCursor("fixture-only-secret");
        UUID owner = UUID.randomUUID();
        var position = new ClassroomDirectory.Position(Instant.parse("2026-10-01T12:00:00.123456Z"), UUID.randomUUID());
        String token = codec.encode(owner, position);
        assertEquals(position, new ClassroomDirectoryCursor("fixture-only-secret").decode(owner, token));
        assertThrows(InvalidClassroomDirectoryRequestException.class, () -> codec.decode(UUID.randomUUID(), token));
        assertThrows(
                InvalidClassroomDirectoryRequestException.class,
                () -> new ClassroomDirectoryCursor("rotated-secret").decode(owner, token));
        for (String invalid : List.of(
                "",
                " ",
                "bad",
                "a.b",
                token + "=",
                "x".repeat(513),
                "A" + token.substring(1),
                token.substring(0, token.indexOf('.') + 1) + "AAAA")) {
            assertThrows(InvalidClassroomDirectoryRequestException.class, () -> codec.decode(owner, invalid));
        }
        assertThrows(ClassroomDirectoryUnavailableException.class, () -> new ClassroomDirectoryCursor("")
                .decode(owner, null));
    }

    @Test
    void invalidInputsNeverQueryAndFetchIsBoundedToOneExtraRow() {
        var repository = mock(ClassroomDirectoryRepository.class);
        var service = new ClassroomDirectoryService(repository, new ClassroomDirectoryCursor("fixture-only-secret"));
        UUID owner = UUID.randomUUID();
        for (int limit : new int[] {-1, 0, 101, Integer.MAX_VALUE}) {
            assertThrows(InvalidClassroomDirectoryRequestException.class, () -> service.listOwned(owner, limit, null));
        }
        assertThrows(InvalidClassroomDirectoryRequestException.class, () -> service.listOwned(owner, 20, "bad"));
        verifyNoInteractions(repository);
        when(repository.findOwned(owner, null, 101)).thenReturn(List.of());
        assertNull(service.listOwned(owner, 100, null).nextCursor());
        verify(repository).findOwned(owner, null, 101);
    }
}

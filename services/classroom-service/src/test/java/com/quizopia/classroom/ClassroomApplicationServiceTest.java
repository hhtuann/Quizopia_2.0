package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.application.ClassroomApplicationService;
import com.quizopia.classroom.application.ClassroomNotFoundException;
import com.quizopia.classroom.application.ClassroomOwnershipDeniedException;
import com.quizopia.classroom.application.ClassroomRecordIdGenerator;
import com.quizopia.classroom.application.ClassroomRepository;
import com.quizopia.classroom.application.CreateClassroomInput;
import com.quizopia.classroom.domain.Classroom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClassroomApplicationServiceTest {
    private final UUID generatedClassroomId = UUID.randomUUID();
    private final RecordingClassroomRepository repository = new RecordingClassroomRepository();
    private final ClassroomRecordIdGenerator idGenerator = () -> generatedClassroomId;
    private ClassroomApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ClassroomApplicationService(repository, idGenerator);
    }

    @Test
    void createsClassroomWithGeneratedIdAndCallerAsOwner() {
        UUID callerUserId = UUID.randomUUID();
        CreateClassroomInput input = new CreateClassroomInput("Study group", "Interdisciplinary", "Mixed ages", null);

        Classroom created = service.create(callerUserId, input);

        assertEquals(generatedClassroomId, created.id());
        assertEquals(callerUserId, created.ownerTeacherUserId());
        assertEquals("Study group", created.name());
        assertEquals("Interdisciplinary", created.subject());
        assertEquals("Mixed ages", created.grade());
        assertNull(created.description());
        assertSame(created, repository.lastInserted);
        assertEquals(1, repository.insertCount);
    }

    @Test
    void creationInputCannotSupplyAnIndependentOwner() {
        assertEquals(
                java.util.List.of("name", "subject", "grade", "description"),
                Arrays.stream(CreateClassroomInput.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
    }

    @Test
    void preservesOptionalDescription() {
        Classroom created = service.create(
                UUID.randomUUID(), new CreateClassroomInput("Class", "Subject", "Grade", "Teacher notes"));

        assertEquals("Teacher notes", created.description());
        assertSame(created, repository.lastInserted);
    }

    @Test
    void domainValidationPropagatesWithoutWriting() {
        UUID callerUserId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(callerUserId, new CreateClassroomInput(" ", "Subject", "Grade", null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(callerUserId, new CreateClassroomInput("Class", "", "Grade", null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(callerUserId, new CreateClassroomInput("Class", "Subject", "\t", null)));
        assertEquals(0, repository.insertCount);
    }

    @Test
    void createRequiresCallerInputAndGeneratedIdWithoutWriting() {
        CreateClassroomInput valid = new CreateClassroomInput("Class", "Subject", "Grade", null);

        assertThrows(NullPointerException.class, () -> service.create(null, valid));
        assertThrows(NullPointerException.class, () -> service.create(UUID.randomUUID(), null));
        assertThrows(NullPointerException.class, () -> new ClassroomApplicationService(repository, () -> null)
                .create(UUID.randomUUID(), valid));
        assertEquals(0, repository.insertCount);
    }

    @Test
    void ownerCanRetrieveOwnedClassroomUsingEqualUserIdValue() {
        UUID owner = UUID.randomUUID();
        Classroom classroom = classroom(owner);
        repository.insert(classroom);

        Classroom retrieved = service.getOwnedClassroom(UUID.fromString(owner.toString()), classroom.id());

        assertSame(classroom, retrieved);
    }

    @Test
    void nonOwnerCannotRetrieveOwnerManagedClassroom() {
        Classroom classroom = classroom(UUID.randomUUID());
        repository.insert(classroom);
        UUID caller = UUID.randomUUID();

        ClassroomOwnershipDeniedException exception = assertThrows(
                ClassroomOwnershipDeniedException.class, () -> service.getOwnedClassroom(caller, classroom.id()));

        assertEquals(classroom.id(), exception.classroomId());
        assertEquals(caller, exception.callerUserId());
    }

    @Test
    void missingClassroomHasDistinctNotFoundOutcome() {
        UUID classroomId = UUID.randomUUID();

        ClassroomNotFoundException exception = assertThrows(
                ClassroomNotFoundException.class, () -> service.getOwnedClassroom(UUID.randomUUID(), classroomId));

        assertEquals(classroomId, exception.classroomId());
    }

    @Test
    void ownerRetrievalRequiresCallerAndClassroomIds() {
        assertThrows(NullPointerException.class, () -> service.getOwnedClassroom(null, UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> service.getOwnedClassroom(UUID.randomUUID(), null));
        assertEquals(0, repository.findCount);
    }

    private Classroom classroom(UUID ownerUserId) {
        return new Classroom(UUID.randomUUID(), ownerUserId, "Class", "Subject", "Grade", null);
    }

    private static final class RecordingClassroomRepository implements ClassroomRepository {
        private final Map<UUID, Classroom> classrooms = new LinkedHashMap<>();
        private Classroom lastInserted;
        private int insertCount;
        private int findCount;

        @Override
        public void insert(Classroom classroom) {
            lastInserted = classroom;
            insertCount++;
            classrooms.put(classroom.id(), classroom);
        }

        @Override
        public Optional<Classroom> findById(UUID id) {
            findCount++;
            return Optional.ofNullable(classrooms.get(id));
        }
    }
}

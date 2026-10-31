package com.quizopia.assessment.application;

import com.quizopia.assessment.application.TeacherAssessmentViews.*;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Local read/receipt port; current remote authority is established outside root transactions. */
public interface TeacherAssessmentStore {
    record Resource(UUID publicationId, UUID classroomId, String state, UUID quizId) {}

    record Filter(int limit, String cursor, UUID studentId, String status, String sort, String direction) {
        public Filter {
            if (limit < 1
                    || limit > 100
                    || status != null
                            && !Set.of("ACTIVE", "SUBMITTED", "ABANDONED").contains(status)
                    || !Set.of("LATEST", "BEST_SCORE").contains(sort)
                    || !Set.of("ASC", "DESC").contains(direction)) throw new PublicationFailure(400, "INVALID_REQUEST");
        }

        public String binding(UUID publication) {
            return publication + ":" + studentId + ":" + status + ":" + sort + ":" + direction;
        }
    }

    Resource resource(ClosureStore.Teacher actor, UUID publication);

    <T> T withAuthorityDeadline(long deadline, Resource resource, Supplier<T> work);

    Overview overview(ClosureStore.Teacher actor, UUID publication);

    Students students(ClosureStore.Teacher actor, UUID publication, Filter filter);

    History history(ClosureStore.Teacher actor, UUID publication, UUID student, int limit, String cursor);

    Review review(ClosureStore.Teacher actor, UUID publication, UUID attempt);
}

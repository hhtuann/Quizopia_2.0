package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.Publication;
import com.quizopia.assessment.domain.PublicationConfiguration.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Safe owner directory projection; never contains configuration, locator or snapshot data. */
public final class PublicationDirectory {
    private PublicationDirectory() {}

    public record Query(int limit, Publication.State state, String search, String cursor) {
        public Query {
            if (limit < 1
                    || limit > 100
                    || (search != null && search.length() > 300)
                    || (cursor != null && (cursor.isEmpty() || cursor.length() > 1024)))
                throw new PublicationFailure(400, "INVALID_REQUEST");
            search = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        }
    }

    public record Summary(
            UUID publicationId,
            String title,
            Audience audience,
            Publication.State state,
            Instant startAt,
            Instant endAt,
            Instant createdAt,
            long revision,
            UUID quizId,
            UUID quizVersionId,
            int versionNumber) {}

    public record Page(List<Summary> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}

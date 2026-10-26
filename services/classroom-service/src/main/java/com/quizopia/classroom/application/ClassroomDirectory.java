package com.quizopia.classroom.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe collection metadata, independent of membership and invitation records. */
public final class ClassroomDirectory {
    private ClassroomDirectory() {}

    public record Position(Instant createdAt, UUID id) {}

    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {"id", "name", "subject", "grade", "description", "createdAt"})
    public record Item(
            UUID id,
            String name,
            String subject,
            String grade,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String description,
            Instant createdAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"items", "nextCursor"})
    public record Page(
            List<Item> items,
            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}

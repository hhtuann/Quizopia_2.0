package com.quizopia.classroom.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomDirectoryService {
    private final ClassroomDirectoryRepository repository;
    private final ClassroomDirectoryCursor cursors;

    public ClassroomDirectoryService(ClassroomDirectoryRepository repository, ClassroomDirectoryCursor cursors) {
        this.repository = repository;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    public ClassroomDirectory.Page listOwned(UUID owner, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new InvalidClassroomDirectoryRequestException();
        var before = cursors.decode(owner, cursor);
        var fetched = repository.findOwned(owner, before, limit + 1);
        var items = fetched.stream().limit(limit).toList();
        String nextCursor = null;
        if (fetched.size() > limit) {
            var last = items.getLast();
            nextCursor = cursors.encode(owner, new ClassroomDirectory.Position(last.createdAt(), last.id()));
        }
        return new ClassroomDirectory.Page(items, nextCursor);
    }
}

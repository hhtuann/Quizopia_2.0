package com.quizopia.classroom.application;

import java.util.List;
import java.util.UUID;

public interface ClassroomDirectoryRepository {
    List<ClassroomDirectory.Item> findOwned(UUID owner, ClassroomDirectory.Position before, int fetchLimit);
}

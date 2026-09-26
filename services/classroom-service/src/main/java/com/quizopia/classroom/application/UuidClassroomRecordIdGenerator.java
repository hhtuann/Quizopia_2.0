package com.quizopia.classroom.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidClassroomRecordIdGenerator implements ClassroomRecordIdGenerator {
    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}

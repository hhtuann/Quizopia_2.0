package com.quizopia.quiz.persistence;

import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionOutboxEntry;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class DeletionJson {
    private final ObjectMapper objectMapper;

    DeletionJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String writeInventory(List<QuizDeletionOperation.VersionRef> versionInventory) {
        try {
            return objectMapper.writeValueAsString(versionInventory);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize deletion version inventory", exception);
        }
    }

    List<QuizDeletionOperation.VersionRef> readInventory(String json) {
        try {
            if (json != null && json.trim().startsWith("{")) {
                // H4 scrubbed ledger state: the inventory was replaced by the bare
                // count {"versionCount":n}; the per-version refs are intentionally gone.
                return List.of();
            }
            return List.of(objectMapper.readValue(json, QuizDeletionOperation.VersionRef[].class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize deletion version inventory", exception);
        }
    }

    String writePayload(QuizDeletionOutboxEntry entry) {
        try {
            return objectMapper.writeValueAsString(new OutboxPayload(
                    entry.operationId(), entry.quizId(), entry.ownerUserId(), entry.versionInventory()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize deletion outbox payload", exception);
        }
    }

    OutboxPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, OutboxPayload.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize deletion outbox payload", exception);
        }
    }

    record OutboxPayload(
            UUID operationId, UUID quizId, UUID ownerUserId, List<QuizDeletionOperation.VersionRef> versionInventory) {}
}

package com.quizopia.quiz.application;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class QuizLibraryCursorCodec {
    private static final byte FORMAT_VERSION = 1;
    private static final int ENCODED_BYTES = Byte.BYTES + Long.BYTES + Integer.BYTES + Long.BYTES + Long.BYTES;

    public String encode(QuizLibraryPosition position) {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_BYTES);
        buffer.put(FORMAT_VERSION);
        buffer.putLong(position.updatedAt().getEpochSecond());
        buffer.putInt(position.updatedAt().getNano());
        buffer.putLong(position.quizId().getMostSignificantBits());
        buffer.putLong(position.quizId().getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public QuizLibraryPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw invalidCursor(null);
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            if (decoded.length != ENCODED_BYTES) {
                throw invalidCursor(null);
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            if (buffer.get() != FORMAT_VERSION) {
                throw invalidCursor(null);
            }

            Instant updatedAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID quizId = new UUID(buffer.getLong(), buffer.getLong());
            return new QuizLibraryPosition(updatedAt, quizId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor(exception);
        }
    }

    private static InvalidQuizLibraryRequestException invalidCursor(Throwable cause) {
        return cause == null
                ? new InvalidQuizLibraryRequestException("Quiz library cursor is invalid")
                : new InvalidQuizLibraryRequestException("Quiz library cursor is invalid", cause);
    }
}

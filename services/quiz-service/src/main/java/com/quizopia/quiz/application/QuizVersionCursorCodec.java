package com.quizopia.quiz.application;

import java.nio.ByteBuffer;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class QuizVersionCursorCodec {
    private static final byte FORMAT_VERSION = 1;
    private static final int ENCODED_BYTES = Byte.BYTES + Integer.BYTES;

    public String encode(int versionNumber) {
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_BYTES);
        buffer.put(FORMAT_VERSION);
        buffer.putInt(versionNumber);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    public int decode(String cursor) {
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
            int versionNumber = buffer.getInt();
            if (versionNumber < 1) {
                throw invalidCursor(null);
            }
            return versionNumber;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor(exception);
        }
    }

    private static InvalidQuizVersionRequestException invalidCursor(Throwable cause) {
        return cause == null
                ? new InvalidQuizVersionRequestException("Quiz version cursor is invalid")
                : new InvalidQuizVersionRequestException("Quiz version cursor is invalid", cause);
    }
}

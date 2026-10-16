package com.quizopia.proctoring.media;

import java.time.Instant;

public interface MediaProvider {
    boolean qualified();

    void ensureRoom(String room);

    String token(String room, String identity, String role, Instant expiresAt);

    void revoke(String room, String identity, Instant cutoff);

    void deleteRoom(String room);
}

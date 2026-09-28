package com.quizopia.identity.security.outbox;

import java.util.Optional;
import javax.crypto.SecretKey;

public interface OutboxPayloadKeyRing {
    String activeKeyVersion();

    SecretKey activeKey();

    Optional<SecretKey> key(String version);
}

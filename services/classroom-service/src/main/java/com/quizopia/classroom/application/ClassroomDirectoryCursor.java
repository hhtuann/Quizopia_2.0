package com.quizopia.classroom.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMAC-authenticated, owner-bound v1 cursor. Never use credentials directly as wire material. */
@Component
public final class ClassroomDirectoryCursor {
    private static final String DOMAIN = "quizopia:classroom:owned-directory:v1";
    private final byte[] key;

    public ClassroomDirectoryCursor(@Value("${quizopia.admission.client-secret:}") String admissionSecret) {
        // Derive a purpose-specific key from the already required service-local credential.
        // Rotation invalidates old cursors; clients restart pagination. No ephemeral node-local key.
        key = admissionSecret == null || admissionSecret.isBlank()
                ? null
                : hmac(admissionSecret.getBytes(StandardCharsets.UTF_8), DOMAIN);
    }

    public void requireAvailable() {
        if (key == null) throw new ClassroomDirectoryUnavailableException();
    }

    public String encode(UUID owner, ClassroomDirectory.Position position) {
        requireAvailable();
        String payload = "1|" + owner + "|" + position.createdAt() + "|" + position.id();
        String data =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        return data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, DOMAIN + "|" + data));
    }

    public ClassroomDirectory.Position decode(UUID owner, String cursor) {
        requireAvailable();
        if (cursor == null) return null;
        try {
            if (cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(signature)
                            .equals(parts[1])
                    || !MessageDigest.isEqual(hmac(key, DOMAIN + "|" + parts[0]), signature))
                throw new IllegalArgumentException();
            String[] fields =
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1") || !fields[1].equals(owner.toString()))
                throw new IllegalArgumentException();
            var position = new ClassroomDirectory.Position(Instant.parse(fields[2]), UUID.fromString(fields[3]));
            if (!encode(owner, position).equals(cursor)) throw new IllegalArgumentException();
            return position;
        } catch (RuntimeException failure) {
            throw new InvalidClassroomDirectoryRequestException();
        }
    }

    private static byte[] hmac(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw new ClassroomDirectoryUnavailableException();
        }
    }
}

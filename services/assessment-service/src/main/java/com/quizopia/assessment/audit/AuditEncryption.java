package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.PublicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** No deployment key/activation binding while custody/privacy approvals are pending. */
public interface AuditEncryption {
    record Protected(String keyId, byte[] bytes) {
        public Protected {
            bytes = bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public String toString() {
            return "ProtectedAudit[redacted]";
        }
    }

    Protected encrypt(String context, byte[] plaintext);

    byte[] decrypt(String keyId, String context, byte[] encrypted);

    @Component
    final class Pending implements AuditEncryption {
        public Protected encrypt(String context, byte[] plaintext) {
            throw unavailable();
        }

        public byte[] decrypt(String keyId, String context, byte[] encrypted) {
            throw unavailable();
        }
    }

    static PublicationFailure unavailable() {
        return new PublicationFailure(503, "AUDIT_UNAVAILABLE");
    }

    /** Explicit constructor for isolated synthetic fixtures, never auto-configured by the application. */
    final class AesGcm implements AuditEncryption {
        private final String keyId;
        private final SecretKeySpec key;
        private final SecureRandom random = new SecureRandom();

        public AesGcm(String keyId, byte[] key) {
            if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,32}") || key == null || key.length != 32)
                throw new IllegalArgumentException("Invalid audit key");
            this.keyId = keyId;
            this.key = new SecretKeySpec(key.clone(), "AES");
        }

        public Protected encrypt(String context, byte[] plaintext) {
            try {
                byte[] nonce = new byte[12];
                random.nextBytes(nonce);
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
                byte[] result = cipher.doFinal(plaintext);
                byte[] envelope = Arrays.copyOf(nonce, nonce.length + result.length);
                System.arraycopy(result, 0, envelope, nonce.length, result.length);
                return new Protected(keyId, envelope);
            } catch (Exception e) {
                throw unavailable();
            }
        }

        public byte[] decrypt(String keyId, String context, byte[] encrypted) {
            try {
                if (!this.keyId.equals(keyId) || encrypted.length < 29) throw unavailable();
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
                return cipher.doFinal(encrypted, 12, encrypted.length - 12);
            } catch (Exception e) {
                throw unavailable();
            }
        }

        public String toString() {
            return "AuditEncryption[redacted]";
        }
    }
}

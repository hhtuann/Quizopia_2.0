package com.quizopia.identity.security.outbox;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmOutboxPayloadCipher implements OutboxPayloadCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AUTHENTICATION_TAG_BITS = 128;

    private final OutboxPayloadKeyRing keyRing;
    private final OutboxNonceGenerator nonceGenerator;

    public AesGcmOutboxPayloadCipher(OutboxPayloadKeyRing keyRing, OutboxNonceGenerator nonceGenerator) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.nonceGenerator = Objects.requireNonNull(nonceGenerator, "nonceGenerator");
    }

    @Override
    public EncryptedOutboxPayload encrypt(RawEmailVerificationOtp rawOtp, OutboxPayloadBinding binding) {
        Objects.requireNonNull(rawOtp, "rawOtp");
        Objects.requireNonNull(binding, "binding");
        byte[] plaintext = rawOtp.value().getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] nonce = nonceGenerator.generate();
            if (nonce == null || nonce.length != SecureOutboxNonceGenerator.NONCE_LENGTH_BYTES) {
                throw new OutboxPayloadEncryptionException();
            }
            String keyVersion = keyRing.activeKeyVersion();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyRing.activeKey(), new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            cipher.updateAAD(canonicalAad(binding, keyVersion));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedOutboxPayload(ciphertext, nonce, keyVersion, binding.payloadFormatVersion());
        } catch (GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof OutboxPayloadEncryptionException encryptionException) {
                throw encryptionException;
            }
            throw new OutboxPayloadEncryptionException();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public RawEmailVerificationOtp decrypt(EncryptedOutboxPayload payload, OutboxPayloadBinding binding) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(binding, "binding");
        if (payload.payloadFormatVersion() != binding.payloadFormatVersion()) {
            throw new OutboxPayloadDecryptionException(OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED);
        }
        var key = keyRing.key(payload.keyVersion())
                .orElseThrow(() ->
                        new OutboxPayloadDecryptionException(OutboxPayloadDecryptionException.Category.MISSING_KEY));
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(AUTHENTICATION_TAG_BITS, payload.nonce()));
            cipher.updateAAD(canonicalAad(binding, payload.keyVersion()));
            plaintext = cipher.doFinal(payload.ciphertext());
            return RawEmailVerificationOtp.from(new String(plaintext, StandardCharsets.US_ASCII));
        } catch (AEADBadTagException exception) {
            throw new OutboxPayloadDecryptionException(OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new OutboxPayloadDecryptionException(OutboxPayloadDecryptionException.Category.DECRYPTION_FAILED);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] canonicalAad(OutboxPayloadBinding binding, String keyVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write("QZEV-AAD".getBytes(StandardCharsets.US_ASCII));
                output.writeByte(1);
                output.writeLong(binding.jobId().getMostSignificantBits());
                output.writeLong(binding.jobId().getLeastSignificantBits());
                writeLengthPrefixed(output, binding.exactRecipientEmail());
                writeLengthPrefixed(output, binding.templateType());
                output.writeLong(binding.otpExpiresAt().getEpochSecond());
                output.writeInt(binding.otpExpiresAt().getNano());
                writeLengthPrefixed(output, keyVersion);
                output.writeInt(binding.payloadFormatVersion());
            }
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Canonical AAD construction failed");
        }
    }

    private static void writeLengthPrefixed(DataOutputStream output, String value) throws java.io.IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}

package com.quizopia.identity.configuration;

import com.quizopia.identity.security.outbox.OutboxPayloadKeyRing;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class ConfiguredOutboxPayloadKeyRing implements OutboxPayloadKeyRing {
    private static final int KEY_LENGTH_BYTES = 32;
    private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String activeKeyVersion;
    private final Map<String, SecretKey> keys;

    public ConfiguredOutboxPayloadKeyRing(OutboxPayloadEncryptionProperties properties) {
        String configuredActiveVersion = properties.getActiveKeyVersion();
        if (configuredActiveVersion == null
                || !KEY_VERSION_PATTERN.matcher(configuredActiveVersion).matches()) {
            throw new IllegalStateException("A valid active outbox encryption key version is required");
        }
        Map<String, SecretKey> decodedKeys = new LinkedHashMap<>();
        properties.getKeys().forEach((version, encodedKey) -> decodedKeys.put(version, decode(version, encodedKey)));
        if (!decodedKeys.containsKey(configuredActiveVersion)) {
            throw new IllegalStateException("The active outbox encryption key version is not configured");
        }
        this.activeKeyVersion = configuredActiveVersion;
        this.keys = Map.copyOf(decodedKeys);
    }

    @Override
    public String activeKeyVersion() {
        return activeKeyVersion;
    }

    @Override
    public SecretKey activeKey() {
        return keys.get(activeKeyVersion);
    }

    @Override
    public Optional<SecretKey> key(String version) {
        return Optional.ofNullable(keys.get(version));
    }

    private static SecretKey decode(String version, String encodedKey) {
        if (version == null || !KEY_VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalStateException("Outbox encryption key versions must use safe non-secret identifiers");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Outbox encryption key is not valid Base64");
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("Outbox encryption keys must decode to exactly 32 bytes");
        }
        try {
            return new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }
}

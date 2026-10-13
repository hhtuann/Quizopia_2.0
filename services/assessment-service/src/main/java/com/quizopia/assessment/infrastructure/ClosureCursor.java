package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.configuration.ClosureProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Versioned HMAC cursor; keys come only from deployment configuration. */
@Component
public final class ClosureCursor {
    private final ClosureProperties properties;
    private final Map<String, byte[]> keys = new HashMap<>();

    public ClosureCursor(ClosureProperties properties) {
        this.properties = properties;
        properties.cursorKeys().forEach((id, value) -> {
            if (value.isEmpty()) return;
            if (!id.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid cursor key identifier");
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 32 || bytes.length > 128)
                throw new IllegalArgumentException("Invalid cursor key length");
            keys.put(id, bytes);
        });
        if ((properties.inboxEnabled() || !keys.isEmpty()) && !keys.containsKey(properties.cursorActiveKey()))
            throw new IllegalArgumentException("Missing active cursor key");
    }

    private byte[] mac(String key, String payload) {
        try {
            var m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(keys.get(key), "HmacSHA256"));
            return m.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    public String encode(UUID actor, String route, String resource, String position) {
        String key = properties.cursorActiveKey();
        if (!keys.containsKey(key)) throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        String payload = "1|" + key + "|" + actor + "|" + route + "|" + resource + "|" + position;
        String data =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        return data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, data));
    }

    public String decode(String cursor, UUID actor, String route, String resource) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            var parts = cursor.split("\\.");
            var fields =
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 6
                    || !fields[0].equals("1")
                    || !keys.containsKey(fields[1])
                    || !fields[2].equals(actor.toString())
                    || !fields[3].equals(route)
                    || !fields[4].equals(resource)
                    || !MessageDigest.isEqual(
                            mac(fields[1], parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            return fields[5];
        } catch (RuntimeException e) {
            throw new PublicationFailure(400, "INVALID_REQUEST");
        }
    }
}

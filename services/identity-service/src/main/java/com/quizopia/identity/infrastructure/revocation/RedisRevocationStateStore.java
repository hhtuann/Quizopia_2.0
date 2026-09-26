package com.quizopia.identity.infrastructure.revocation;

import com.quizopia.identity.application.revocation.AuthoritativeRevocationState;
import com.quizopia.identity.application.revocation.RedisRevocationLookup;
import com.quizopia.identity.application.revocation.RedisRevocationPropagationResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRevocationStateStore {
    private static final RedisScript<Long> MONOTONIC_WRITE_SCRIPT = RedisScript.of(
            """
            local current = redis.call('GET', KEYS[1])
            if current == false or tonumber(ARGV[1]) > tonumber(current) then
              redis.call('SET', KEYS[1], ARGV[1])
              return 1
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRevocationStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RedisRevocationPropagationResult publish(AuthoritativeRevocationState state) {
        try {
            Long result = redisTemplate.execute(
                    MONOTONIC_WRITE_SCRIPT,
                    List.of(RedisRevocationKey.forUser(state.userId())),
                    Long.toString(state.revokedBefore().toEpochMilli()));
            if (result == null) {
                return RedisRevocationPropagationResult.unavailable();
            }
            return RedisRevocationPropagationResult.propagated();
        } catch (RuntimeException exception) {
            return RedisRevocationPropagationResult.unavailable();
        }
    }

    public RedisRevocationLookup lookup(UUID userId) {
        try {
            String value = redisTemplate.opsForValue().get(RedisRevocationKey.forUser(userId));
            if (value == null) {
                return RedisRevocationLookup.notPresent();
            }
            return RedisRevocationLookup.present(Instant.ofEpochMilli(Long.parseLong(value)));
        } catch (RuntimeException exception) {
            return RedisRevocationLookup.unavailable();
        }
    }
}

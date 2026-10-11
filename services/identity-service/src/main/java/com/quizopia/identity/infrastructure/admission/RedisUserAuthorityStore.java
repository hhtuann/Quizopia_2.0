package com.quizopia.identity.infrastructure.admission;

import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.application.admission.UserAuthorityFacts;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisUserAuthorityStore {
    private final StringRedisTemplate redis;
    private final UserAuthorityCodec codec;

    public RedisUserAuthorityStore(StringRedisTemplate redis, UserAuthorityCodec codec) {
        this.redis = redis;
        this.codec = codec;
    }

    public static String key(UUID userId) {
        return "quizopia:identity:user-authority:v1:" + userId;
    }

    public UserAuthorityFacts read(UUID actor) {
        try {
            var facts = codec.decode(redis.opsForValue().get(key(actor)));
            if (!facts.userId().equals(actor)) throw AdmissionFailure.unavailable();
            return facts;
        } catch (RuntimeException exception) {
            throw AdmissionFailure.unavailable();
        }
    }
    /** A stale writer may reduce availability, but equality against primary always prevents a stale grant. */
    public void publish(UserAuthorityFacts facts) {
        try {
            redis.opsForValue().set(key(facts.userId()), codec.encode(facts));
        } catch (RuntimeException exception) {
            throw AdmissionFailure.unavailable();
        }
    }
}

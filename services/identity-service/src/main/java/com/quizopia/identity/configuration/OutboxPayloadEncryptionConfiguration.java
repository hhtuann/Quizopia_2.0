package com.quizopia.identity.configuration;

import com.quizopia.identity.security.outbox.AesGcmOutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxNonceGenerator;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadKeyRing;
import com.quizopia.identity.security.outbox.SecureOutboxNonceGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxPayloadEncryptionProperties.class)
public class OutboxPayloadEncryptionConfiguration {
    @Bean
    OutboxPayloadKeyRing outboxPayloadKeyRing(OutboxPayloadEncryptionProperties properties) {
        return new ConfiguredOutboxPayloadKeyRing(properties);
    }

    @Bean
    OutboxNonceGenerator outboxNonceGenerator() {
        return new SecureOutboxNonceGenerator();
    }

    @Bean
    OutboxPayloadCipher outboxPayloadCipher(OutboxPayloadKeyRing keyRing, OutboxNonceGenerator nonceGenerator) {
        return new AesGcmOutboxPayloadCipher(keyRing, nonceGenerator);
    }
}

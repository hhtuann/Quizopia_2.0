package com.quizopia.proctoring.media;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.media.infrastructure.LiveKitMediaProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "quizopia.proctoring.media.enabled", havingValue = "true")
    LiveKitMediaProvider mediaProvider(MediaProperties p, MonitoringProperties monitoring, Environment env) {
        p.validate(env.matchesProfiles("prod"));
        if (!monitoring.isEnabled()) throw new IllegalStateException("Media requires durable monitoring");
        return new LiveKitMediaProvider(p);
    }

    @Bean
    @ConditionalOnProperty(name = "quizopia.proctoring.media.enabled", havingValue = "true")
    MediaService mediaService(
            MonitoringStore sessions,
            MonitoringAuthority authority,
            MediaStore store,
            MediaProvider provider,
            MediaProperties p) {
        return new MediaService(sessions, authority, store, provider, p);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = {"quizopia.proctoring.media.enabled", "quizopia.proctoring.media.worker-enabled"},
            havingValue = "true")
    static class RecoveryConfiguration {
        @Bean
        MediaRecovery mediaRecovery(MediaStore store, MediaProvider provider, MeterRegistry registry) {
            return new MediaRecovery(store, provider, registry);
        }

        @Bean
        RecoverySchedule mediaSchedule(MediaRecovery recovery) {
            return new RecoverySchedule(recovery);
        }
    }

    static class RecoverySchedule {
        private final MediaRecovery recovery;

        RecoverySchedule(MediaRecovery r) {
            recovery = r;
        }

        @Scheduled(fixedDelay = 3000, initialDelay = 3000)
        public void tick() {
            recovery.tick();
        }
    }
}

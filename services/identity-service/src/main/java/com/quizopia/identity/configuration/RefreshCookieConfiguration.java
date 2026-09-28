package com.quizopia.identity.configuration;

import com.quizopia.identity.api.auth.RefreshCookieFactory;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class RefreshCookieConfiguration {
    @Bean
    @Profile("prod")
    RefreshCookieFactory productionRefreshCookieFactory(@Qualifier("identityClock") Clock clock) {
        return new RefreshCookieFactory(clock, RefreshCookieFactory.SecurityMode.PRODUCTION);
    }

    @Bean
    @Profile({"local", "dev", "test", "persistence-test"})
    RefreshCookieFactory localRefreshCookieFactory(@Qualifier("identityClock") Clock clock) {
        return new RefreshCookieFactory(clock, RefreshCookieFactory.SecurityMode.LOCAL_DEVELOPMENT);
    }
}

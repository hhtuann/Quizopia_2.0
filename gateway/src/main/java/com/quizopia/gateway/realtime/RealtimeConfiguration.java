package com.quizopia.gateway.realtime;

import com.quizopia.gateway.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfiguration {
    @Bean
    Clock realtimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    RealtimeTicketStore realtimeTicketStore(
            ReactiveStringRedisTemplate redis, RealtimeProperties properties, Clock realtimeClock) {
        return new RealtimeTicketStore(redis, properties, realtimeClock);
    }

    @Bean
    RealtimeConnectionLimiter realtimeConnectionLimiter(RealtimeProperties properties, Clock realtimeClock) {
        return new RealtimeConnectionLimiter(properties, realtimeClock);
    }

    @Bean
    RealtimeWebSocketHandler realtimeWebSocketHandler(
            RealtimeProperties properties,
            RealtimeTicketStore tickets,
            ReactiveJwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            IdentityAdmissionClient admission,
            MeterRegistry meters,
            Clock realtimeClock) {
        return new RealtimeWebSocketHandler(properties, tickets, decoder, converter, admission, meters, realtimeClock);
    }

    @Bean
    SimpleUrlHandlerMapping realtimeWebSocketMapping(RealtimeWebSocketHandler handler) {
        var mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(-2);
        mapping.setUrlMap(Map.of(RealtimeIngressFilter.WEBSOCKET_PATH, handler));
        return mapping;
    }

    @Bean
    WebSocketHandlerAdapter realtimeWebSocketHandlerAdapter(RealtimeProperties properties) {
        var strategy = new ReactorNettyRequestUpgradeStrategy(() -> WebsocketServerSpec.builder()
                .protocols(RealtimeIngressFilter.SUBPROTOCOL)
                .maxFramePayloadLength(65_536)
                .handlePing(false)
                .compress(false));
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(strategy));
    }
}

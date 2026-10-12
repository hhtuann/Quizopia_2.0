package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.quizopia.gateway.security.admission.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

class UserAdmissionCancellationTest {
    ServerWebExchange exchange() {
        var jwt = Jwt.withTokenValue("original.payload.signature")
                .header("alg", "RS256")
                .subject(AdmissionTestTokens.ACTOR)
                .build();
        return MockServerWebExchange.from(MockServerHttpRequest.post("/classrooms")
                        .header("Authorization", "Bearer original.payload.signature"))
                .mutate()
                .principal(Mono.just(new JwtAuthenticationToken(jwt, List.of())))
                .build();
    }

    @Test
    void cancelledAdmissionCancelsDependencyAndNeverStartsDownstream() {
        var cancelled = new AtomicBoolean();
        var downstream = new AtomicInteger();
        var client = mock(IdentityAdmissionClient.class);
        when(client.admit(any(), any()))
                .thenReturn(Mono.<Void>never().doFinally(signal -> cancelled.set(signal == SignalType.CANCEL)));
        var subscription = new UserAdmissionWebFilter(
                        client, new com.quizopia.gateway.security.admission.PublicationProperties())
                .filter(exchange(), value -> {
                    downstream.incrementAndGet();
                    return Mono.empty();
                })
                .subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
        assertEquals(0, downstream.get());
    }

    @Test
    void admissionFailureCannotContinueDownstream() {
        var downstream = new AtomicInteger();
        var client = mock(IdentityAdmissionClient.class);
        when(client.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        var exchange = exchange();
        new UserAdmissionWebFilter(client, new com.quizopia.gateway.security.admission.PublicationProperties())
                .filter(exchange, value -> {
                    downstream.incrementAndGet();
                    return Mono.empty();
                })
                .block();
        verify(client).admit(any(), any());
        assertEquals(503, exchange.getResponse().getStatusCode().value());
        assertEquals(0, downstream.get());
    }

    @Test
    void downstreamErrorAfterAdmissionRetainsItsOwnClassification() {
        var client = mock(IdentityAdmissionClient.class);
        when(client.admit(any(), any())).thenReturn(Mono.empty());
        var downstream = new IllegalStateException("Business dependency failure");
        var error = assertThrows(IllegalStateException.class, () -> new UserAdmissionWebFilter(
                        client, new com.quizopia.gateway.security.admission.PublicationProperties())
                .filter(exchange(), value -> Mono.error(downstream))
                .block());
        assertSame(downstream, error);
    }
}

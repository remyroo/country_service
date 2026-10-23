package com.rehema.country_service.client;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.rehema.country_service.config.SoapResilienceConfig;
import com.rehema.country_service.exception.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class SoapResilienceTests {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final SoapResilience resilience = new SoapResilienceConfig().soapResilience(
            metrics, 2, Duration.ZERO, 10, 4, 50, Duration.ofSeconds(10));

    @ParameterizedTest
    @EnumSource(value = SoapServiceException.Reason.class, names = {"UNAVAILABLE", "TIMEOUT"})
    void retriesTransientFailureOnceAndRecordsRecovery(SoapServiceException.Reason reason) {
        AtomicInteger attempts = new AtomicInteger();
        String result = resilience.execute("CountryISOCode", () -> {
            if (attempts.incrementAndGet() == 1) throw new SoapServiceException(reason, null);
            return "KE";
        });
        assertThat(result).isEqualTo("KE");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(metrics.get("country.soap.retries").counter().count()).isEqualTo(1);
        assertThat(metrics.get("country.soap.operation").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void exhaustedTimeoutPreservesFallbackReasonAndStopsAtTwoAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> resilience.execute("FullCountryInfo", () -> {
            attempts.incrementAndGet();
            throw new SoapServiceException(SoapServiceException.Reason.TIMEOUT, null);
        })).isInstanceOfSatisfying(SoapServiceException.class,
                failure -> assertThat(failure.getReason()).isEqualTo(SoapServiceException.Reason.TIMEOUT));
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(metrics.get("country.soap.operation").tag("outcome", "timeout").timer().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("nonTransientFailures")
    void neverRetriesOrCountsNonTransientFailures(RuntimeException failure) {
        AtomicInteger attempts = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resilience.execute("CountryISOCode", () -> {
                attempts.incrementAndGet();
                throw failure;
            })).isSameAs(failure);
        }
        assertThat(attempts.get()).isEqualTo(5);
        CircuitBreaker breaker = breaker();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    static Stream<RuntimeException> nonTransientFailures() {
        return Stream.of(new UnknownCountryException(), new InvalidSoapResponseException("invalid"),
                new SoapServiceException(SoapServiceException.Reason.FAULT, null));
    }

    @Test
    void sharedCircuitRejectsWithoutAttemptOrRetryThenRecovers() {
        AtomicInteger attempts = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> resilience.execute("CountryISOCode", () -> {
                attempts.incrementAndGet();
                throw new SoapServiceException(SoapServiceException.Reason.UNAVAILABLE, null);
            })).isInstanceOf(SoapServiceException.class);
        }
        assertThat(attempts.get()).isEqualTo(8);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> resilience.execute("FullCountryInfo", () -> {
            attempts.incrementAndGet();
            return "must not execute";
        })).isInstanceOfSatisfying(SoapServiceException.class,
                failure -> assertThat(failure.getReason()).isEqualTo(SoapServiceException.Reason.CIRCUIT_OPEN));
        assertThat(attempts.get()).isEqualTo(8);
        assertThat(metrics.get("country.soap.rejected").counter().count()).isEqualTo(1);
        assertThat(metrics.get("country.soap.retries").counter().count()).isEqualTo(4);
        assertThat(metrics.get("country.soap.circuit.state").tag("state", "OPEN").gauge().value()).isEqualTo(1);

        // Exercise the recovery probe without sleeping through the production wait interval.
        breaker().transitionToHalfOpenState();
        assertThat(resilience.execute("FullCountryInfo", () -> "recovered")).isEqualTo("recovered");
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(metrics.get("country.soap.circuit.state").tag("state", "CLOSED").gauge().value()).isEqualTo(1);
    }

    private CircuitBreaker breaker() {
        return (CircuitBreaker) ReflectionTestUtils.getField(resilience, "breaker");
    }
}

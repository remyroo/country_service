package com.rehema.country_service.config;

import java.time.Duration;
import com.rehema.country_service.client.SoapResilience;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SoapResilienceConfig {
    @Bean
    public SoapResilience soapResilience(MeterRegistry metrics,
            @Value("${country.soap.retry.max-attempts}") int attempts,
            @Value("${country.soap.retry.delay}") Duration delay,
            @Value("${country.soap.circuit.window-size}") int window,
            @Value("${country.soap.circuit.minimum-calls}") int minimum,
            @Value("${country.soap.circuit.failure-rate}") float failureRate,
            @Value("${country.soap.circuit.open-duration}") Duration openDuration) {
        if (attempts < 1 || attempts > 3 || delay.isNegative() || minimum > window
                || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Invalid SOAP resilience configuration");
        }
        RetryConfig retry = RetryConfig.custom().maxAttempts(attempts).waitDuration(delay)
                .retryOnException(SoapResilience::isTransient).build();
        CircuitBreakerConfig circuit = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window).minimumNumberOfCalls(minimum).failureRateThreshold(failureRate)
                .waitDurationInOpenState(openDuration).permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(failure -> !SoapResilience.isTransient(failure)).build();
        return new SoapResilience(CircuitBreaker.of("countrySoap", circuit), retry, metrics);
    }
}

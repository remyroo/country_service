package com.rehema.country_service.client;

import java.util.function.Supplier;
import com.rehema.country_service.exception.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SoapResilience {
    private static final Logger log = LoggerFactory.getLogger(SoapResilience.class);
    private final CircuitBreaker breaker;
    private final RetryConfig retryConfig;
    private final MeterRegistry metrics;

    public SoapResilience(CircuitBreaker breaker, RetryConfig retryConfig, MeterRegistry metrics) {
        this.breaker = breaker;
        this.retryConfig = retryConfig;
        this.metrics = metrics;
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            metrics.gauge("country.soap.circuit.state", java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("state", state.name())), breaker,
                    circuit -> circuit.getState() == state ? 1 : 0);
        }
        breaker.getEventPublisher().onStateTransition(event -> log.atInfo()
                .addKeyValue("transition", event.getStateTransition().name()).log("SOAP circuit state changed"));
    }

    public static boolean isTransient(Throwable failure) {
        return failure instanceof SoapServiceException exception
                && (exception.getReason() == SoapServiceException.Reason.UNAVAILABLE
                    || exception.getReason() == SoapServiceException.Reason.TIMEOUT);
    }

    public <T> T execute(String operation, Supplier<T> work) {
        Retry retry = Retry.of(operation, retryConfig);
        retry.getEventPublisher().onRetry(event -> {
            metrics.counter("country.soap.retries", "operation", operation).increment();
            log.atWarn().addKeyValue("operation", operation)
                    .addKeyValue("retry", event.getNumberOfRetryAttempts()).log("Retrying SOAP operation");
        });
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "success";
        try {
            // The breaker counts the final outcome, not each retry attempt.
            return breaker.executeSupplier(() -> retry.executeSupplier(() -> {
                metrics.counter("country.soap.attempts", "operation", operation).increment();
                return work.get();
            }));
        } catch (CallNotPermittedException exception) {
            outcome = "circuit_open";
            metrics.counter("country.soap.rejected", "operation", operation).increment();
            throw new SoapServiceException(SoapServiceException.Reason.CIRCUIT_OPEN, exception);
        } catch (SoapServiceException exception) {
            outcome = exception.getReason().name().toLowerCase(java.util.Locale.ROOT);
            throw exception;
        } catch (UnknownCountryException exception) {
            outcome = "unknown_country";
            throw exception;
        } catch (InvalidSoapResponseException exception) {
            outcome = "invalid_response";
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            sample.stop(metrics.timer("country.soap.operation", "operation", operation, "outcome", outcome));
            log.atInfo().addKeyValue("operation", operation).addKeyValue("outcome", outcome)
                    .log("SOAP operation completed");
        }
    }
}

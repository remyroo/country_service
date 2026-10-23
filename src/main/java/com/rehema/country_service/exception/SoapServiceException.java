package com.rehema.country_service.exception;

public class SoapServiceException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, FAULT }

    private final Reason reason;

    public SoapServiceException(Reason reason, Throwable cause) {
        super(switch (reason) {
            case UNAVAILABLE -> "Country information service is unavailable";
            case TIMEOUT -> "Country information service timed out";
            case FAULT -> "Country information service returned a SOAP fault";
        }, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

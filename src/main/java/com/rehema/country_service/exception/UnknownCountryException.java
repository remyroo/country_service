package com.rehema.country_service.exception;

public class UnknownCountryException extends RuntimeException {
    public UnknownCountryException() {
        super("Country was not found by the external service");
    }
}

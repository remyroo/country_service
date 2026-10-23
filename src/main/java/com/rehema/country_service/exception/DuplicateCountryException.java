package com.rehema.country_service.exception;

public class DuplicateCountryException extends RuntimeException {
    public DuplicateCountryException(Throwable cause) {
        super("Country is already stored", cause);
    }
}

package com.rehema.country_service.exception;

public class CountryNotFoundException extends RuntimeException {
    public CountryNotFoundException(Long id) {
        super("Stored country not found: " + id);
    }
}

package com.rehema.country_service.exception;

public class InvalidSoapResponseException extends RuntimeException {
    public InvalidSoapResponseException(String message) {
        super(message);
    }

    public InvalidSoapResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}

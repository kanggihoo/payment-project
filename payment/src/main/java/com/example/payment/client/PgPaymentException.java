package com.example.payment.client;

public class PgPaymentException extends RuntimeException {

    public PgPaymentException(String message) {
        super(message);
    }

    public PgPaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}

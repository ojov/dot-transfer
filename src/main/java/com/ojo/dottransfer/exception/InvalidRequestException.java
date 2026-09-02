package com.ojo.dottransfer.exception;

/**
 * The request is structurally valid but semantically impossible - the same account on both sides of
 * a transfer, mismatched currencies, an inverted date range, a summary of a future day.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}

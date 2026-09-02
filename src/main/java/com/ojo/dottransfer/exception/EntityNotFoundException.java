package com.ojo.dottransfer.exception;

/** A requested record does not exist. */
public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String message) {
        super(message);
    }
}

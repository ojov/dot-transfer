package com.ojo.dottransfer.exception;

/** An account involved in the transfer is not ACTIVE and cannot move money. */
public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException(String message) {
        super(message);
    }
}

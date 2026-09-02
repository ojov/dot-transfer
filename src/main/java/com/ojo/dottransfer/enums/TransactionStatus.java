package com.ojo.dottransfer.enums;

/**
 * Terminal outcomes of a transfer attempt. There is no PENDING state: a transfer is resolved
 * synchronously inside one database transaction, so a record only ever exists having already
 * succeeded or failed.
 */
public enum TransactionStatus {
    SUCCESSFUL,
    INSUFFICIENT_FUND,
    FAILED
}

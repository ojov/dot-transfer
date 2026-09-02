package com.ojo.dottransfer.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Stable, machine-readable outcome codes carried on every {@code DotApiResponse}. Clients branch on
 * {@code code}; the HTTP status alone is too coarse (a rejected transfer and a malformed request are
 * both "not a success" but need very different handling).
 */
@Getter
@RequiredArgsConstructor
public enum ResponseCode {

    SUCCESS("00", "Success"),
    INVALID_REQUEST("01", "Invalid request"),
    RESOURCE_NOT_FOUND("02", "Resource not found"),
    INSUFFICIENT_FUND("03", "Insufficient fund"),
    ACCOUNT_NOT_ACTIVE("04", "Account not active"),
    DUPLICATE_REQUEST("05", "Duplicate request"),
    TRANSFER_FAILED("06", "Transfer failed"),
    INTERNAL_ERROR("99", "Internal server error");

    private final String code;
    private final String description;
}

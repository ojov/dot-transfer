package com.ojo.dottransfer.models.dto;

import com.ojo.dottransfer.enums.TransactionStatus;

import java.math.BigDecimal;

/**
 * One status bucket of a day's transactions, straight from a {@code group by} in the database.
 *
 * <p>Aggregating in SQL and assembling the buckets in Java keeps the query readable and means a
 * day's rows are never loaded into memory to be counted.
 */
public record StatusAggregate(
        TransactionStatus status,
        long count,
        BigDecimal totalAmount,
        BigDecimal totalFees,
        BigDecimal totalCommission,
        long commissionWorthyCount) {}

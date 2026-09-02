package com.ojo.dottransfer.models.responses;

import com.ojo.dottransfer.enums.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(

        @Schema(example = "DOT-20260902-K3PZ81QW6MTD")
        String transactionReference,

        @Schema(description = "The business day this transfer belongs to")
        LocalDate transactionDate,

        String sourceAccountNumber,
        String destinationAccountNumber,

        @Schema(description = "What the recipient receives")
        BigDecimal amount,

        @Schema(description = "0.5% of the amount, capped at 100")
        BigDecimal transactionFee,

        @Schema(description = "amount + transactionFee - what the sender is debited")
        BigDecimal billedAmount,

        String currency,
        String description,

        @Schema(description = "SUCCESSFUL, INSUFFICIENT_FUND or FAILED")
        TransactionStatus status,

        @Schema(example = "Balance 40000.00 is below the required 50250.00")
        String statusMessage,

        @Schema(description = "Null until the nightly commission job has assessed this transaction")
        Boolean commissionWorthy,

        BigDecimal commission,
        Instant commissionComputedAt,
        Instant createdAt) {}

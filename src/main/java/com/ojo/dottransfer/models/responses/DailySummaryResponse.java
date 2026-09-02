package com.ojo.dottransfer.models.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record DailySummaryResponse(

        LocalDate date,

        @Schema(description = "True when the day is still open, so these totals can still change")
        boolean provisional,

        @Schema(description = "True when served from the stored nightly snapshot rather than computed now")
        boolean fromSnapshot,

        Instant generatedAt,

        long totalCount,
        long successfulCount,
        long failedCount,
        long insufficientFundCount,

        @Schema(description = "Successful transactions the commission job marked as commission-worthy")
        long commissionWorthyCount,

        @Schema(description = "Volume attempted, across every status")
        BigDecimal totalAmount,

        @Schema(description = "Volume that actually moved")
        BigDecimal successfulAmount,

        @Schema(description = "Fees on successful transactions only")
        BigDecimal totalFees,

        BigDecimal totalCommission) {}

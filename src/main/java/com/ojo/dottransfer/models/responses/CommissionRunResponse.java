package com.ojo.dottransfer.models.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What one commission run did. Carries a list of days rather than a single one because a run may
 * catch up on several at once after an outage - a normal night contains exactly one.
 */
public record CommissionRunResponse(

        @Schema(description = "The business days this run assessed, most recent first. Empty when there was nothing outstanding.")
        List<LocalDate> dates,

        @Schema(description = "Transactions assessed. Zero means every day was already done.")
        int assessed,

        @Schema(description = "Of those, how many earned commission")
        int commissionWorthy,

        BigDecimal totalCommission) {}

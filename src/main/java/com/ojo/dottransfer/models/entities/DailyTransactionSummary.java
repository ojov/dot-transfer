package com.ojo.dottransfer.models.entities;

import com.ojo.dottransfer.models.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A persisted snapshot of one closed business day, written by the nightly job.
 *
 * <p>Unique on {@code summaryDate}, so re-running the job for a day updates the existing row rather
 * than accumulating duplicates - which also means a job that fails halfway can simply be run again.
 */
@Entity
@Table(name = "daily_transaction_summaries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_daily_summaries_date", columnNames = "summary_date"))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DailyTransactionSummary extends BaseEntity {

    @Column(name = "summary_date", nullable = false, updatable = false)
    private LocalDate summaryDate;

    @Column(nullable = false)
    private long totalCount;

    @Column(nullable = false)
    private long successfulCount;

    @Column(nullable = false)
    private long failedCount;

    @Column(nullable = false)
    private long insufficientFundCount;

    @Column(nullable = false)
    private long commissionWorthyCount;

    /** Volume attempted, across every status. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    /** Volume that actually moved. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal successfulAmount;

    /** Fees on successful transactions only - a rejected transfer is assessed a fee but never charged it. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalFees;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalCommission;

    /** When this snapshot was produced, so a stale one is obvious. */
    @Column(nullable = false)
    private Instant generatedAt;
}

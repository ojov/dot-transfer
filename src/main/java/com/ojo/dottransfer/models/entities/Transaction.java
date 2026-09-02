package com.ojo.dottransfer.models.entities;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One transfer attempt, successful or not.
 *
 * <p>A ledger row must stay truthful forever, so the account numbers and currency are copied onto it
 * rather than only reached through the relations: the record still reads correctly after an account
 * is closed, and filtering by account number needs no join. The relations are kept alongside for
 * referential integrity.
 *
 * <p>Failed attempts are first-class rows - they carry the fee and billed amount they were assessed
 * against, which is what explains <em>why</em> an INSUFFICIENT_FUND rejection happened. No money
 * moves for them.
 */
@Entity
@Table(name = "transactions", indexes = {
        // The daily summary aggregate: one day, grouped by status.
        @Index(name = "idx_txn_date_status", columnList = "transaction_date, status"),
        // Listing filtered by account number, which must match either side of the transfer.
        @Index(name = "idx_txn_source_account", columnList = "source_account_number"),
        @Index(name = "idx_txn_destination_account", columnList = "destination_account_number"),
        // The commission job's claim query: one day, not yet assessed.
        @Index(name = "idx_txn_date_commission", columnList = "transaction_date, commission_worthy")
})
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false, length = 40)
    private String transactionReference;

    /**
     * Caller-supplied de-duplication key. The unique constraint is what makes a retry safe across
     * instances: two pods handling the same retried request cannot both insert, so the loser reads
     * back the winner's row instead of debiting twice.
     */
    @Column(unique = true, length = 64)
    private String idempotencyKey;

    /**
     * The business day this transfer belongs to, fixed once at creation in the configured business
     * zone. Stored rather than derived from {@code createdAt} so the summary and date-range filters
     * are indexed single-column lookups instead of timezone conversions the index cannot serve -
     * and so every instance agrees on which day a transaction landed on.
     */
    @Column(name = "transaction_date", nullable = false, updatable = false)
    private LocalDate transactionDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;

    @Column(name = "source_account_number", nullable = false, updatable = false, length = 20)
    private String sourceAccountNumber;

    @Column(name = "destination_account_number", nullable = false, updatable = false, length = 20)
    private String destinationAccountNumber;

    /** What the recipient receives. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** 0.5% of {@link #amount}, capped. Assessed on every attempt, collected only on success. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal transactionFee;

    /** {@code amount + transactionFee} - what the sender is debited, and the balance the check demands. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal billedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionStatus status;

    @Column(length = 255)
    private String statusMessage;

    /**
     * {@code null} means not yet assessed - the sentinel the nightly commission job claims work
     * with. After the job has run for a day, no row from that day is left null.
     */
    @Column(name = "commission_worthy")
    private Boolean commissionWorthy;

    @Column(precision = 19, scale = 2)
    private BigDecimal commission;

    /** When the commission job assessed this row. Evidence the job ran, rather than an inference. */
    private Instant commissionComputedAt;
}

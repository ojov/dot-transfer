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

@Entity
@Table(name = "transactions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false, length = 40)
    private String transactionReference;

    @Column(unique = true, length = 64) private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(precision = 19, scale = 4)
    private BigDecimal transactionFee;
    @Column(precision = 19, scale = 4)
    private BigDecimal billedAmount;

    private String description;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private TransactionStatus status;

    private String statusMessage;
    private Boolean commissionWorthy;
    @Column(precision = 19, scale = 4) private BigDecimal commission;
}
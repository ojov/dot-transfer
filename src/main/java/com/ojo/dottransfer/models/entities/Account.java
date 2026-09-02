package com.ojo.dottransfer.models.entities;

import com.ojo.dottransfer.enums.AccountStatus;
import com.ojo.dottransfer.enums.AccountType;
import com.ojo.dottransfer.models.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Entity
@Table(name = "accounts",
        indexes = @Index(name = "idx_accounts_customer", columnList = "customer_id"))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Account extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "NGN";
}
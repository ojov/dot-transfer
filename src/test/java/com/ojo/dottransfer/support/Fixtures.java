package com.ojo.dottransfer.support;

import com.ojo.dottransfer.enums.AccountStatus;
import com.ojo.dottransfer.enums.AccountType;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.entities.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test data builders. Each returns a valid object with sensible defaults so a test only has to
 * state the one or two fields it actually cares about - the rest is noise that obscures intent.
 */
public final class Fixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Fixtures() {}

    public static Customer customer() {
        int n = SEQUENCE.incrementAndGet();
        return Customer.builder()
                .firstName("Test").lastName("Customer " + n)
                .email("customer%d@example.com".formatted(n))
                .phoneNumber("080%08d".formatted(n))
                .build();
    }

    public static Account account(Customer customer, String accountNumber, String balance) {
        return account(customer, accountNumber, balance, AccountStatus.ACTIVE);
    }

    public static Account account(Customer customer, String accountNumber, String balance,
                                  AccountStatus status) {
        return Account.builder()
                .customer(customer)
                .accountNumber(accountNumber)
                .accountType(AccountType.SAVINGS)
                .status(status)
                .balance(new BigDecimal(balance))
                .currency("NGN")
                .build();
    }

    /** A transaction with every non-null column populated; override what the test is about. */
    public static Transaction.TransactionBuilder<?, ?> transaction(
            Account source, Account destination, LocalDate date) {

        return Transaction.builder()
                .transactionReference("REF-" + SEQUENCE.incrementAndGet())
                .transactionDate(date)
                .sourceAccount(source)
                .destinationAccount(destination)
                .sourceAccountNumber(source.getAccountNumber())
                .destinationAccountNumber(destination.getAccountNumber())
                .amount(new BigDecimal("1000.00"))
                .transactionFee(new BigDecimal("5.00"))
                .billedAmount(new BigDecimal("1005.00"))
                .currency("NGN")
                .status(TransactionStatus.SUCCESSFUL)
                .statusMessage("Transfer completed");
    }
}

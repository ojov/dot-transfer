package com.ojo.dottransfer.config;

import com.ojo.dottransfer.enums.AccountStatus;
import com.ojo.dottransfer.enums.AccountType;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.repositories.AccountRepository;
import com.ojo.dottransfer.repositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a handful of accounts so the API can be exercised immediately after startup, including the
 * cases worth demonstrating: a well-funded account, a nearly-empty one for INSUFFICIENT_FUND, and a
 * frozen one for the inactive-account rejection.
 *
 * <p>Dev profile only - this must never run against a real database.
 *
 * <p>The seed runs through a {@link TransactionTemplate} rather than an {@code @Transactional}
 * method so that the catch sits <em>outside</em> the transaction boundary. Catching a constraint
 * violation inside the transaction that raised it does not help: the transaction is already marked
 * rollback-only, and returning normally simply fails again at commit. Instances starting together
 * would otherwise all see an empty table and all try to seed it.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.count() > 0) {
            log.info("Seed data already present; skipping");
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> seed());
            log.info("Seeded 2 customers and 4 accounts (1000000001-1000000004)");
        } catch (DataIntegrityViolationException ex) {
            // Another instance won the race and seeded first. Nothing to do.
            log.info("Seed data was written concurrently by another instance; skipping");
        }
    }

    private void seed() {
        Customer ada = customerRepository.save(Customer.builder()
                .firstName("Ada").lastName("Obi")
                .email("ada.obi@example.com").phoneNumber("08010000001")
                .build());

        Customer bola = customerRepository.save(Customer.builder()
                .firstName("Bola").lastName("Ade")
                .email("bola.ade@example.com").phoneNumber("08010000002")
                .build());

        accountRepository.saveAll(List.of(
                account(ada, "1000000001", AccountType.SAVINGS, AccountStatus.ACTIVE, "500000.00"),
                account(bola, "1000000002", AccountType.CURRENT, AccountStatus.ACTIVE, "250000.00"),
                account(ada, "1000000003", AccountType.SAVINGS, AccountStatus.ACTIVE, "1000.00"),
                account(bola, "1000000004", AccountType.CURRENT, AccountStatus.FROZEN, "75000.00")));
    }

    private static Account account(Customer customer, String number, AccountType type,
                                   AccountStatus status, String balance) {
        return Account.builder()
                .customer(customer)
                .accountNumber(number)
                .accountType(type)
                .status(status)
                .balance(new BigDecimal(balance))
                .currency("NGN")
                .build();
    }
}

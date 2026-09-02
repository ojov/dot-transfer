package com.ojo.dottransfer.repositories;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.dto.StatusAggregate;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.repositories.specification.TransactionSpecifications;
import com.ojo.dottransfer.support.Fixtures;
import com.ojo.dottransfer.config.JpaAuditingConfig;
import com.ojo.dottransfer.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queries and constraints, exercised against the real schema Flyway builds.
 *
 * <p>{@code replace = NONE} keeps Spring from swapping in an embedded database - the whole point is
 * to run against Postgres. Each test method is wrapped in a transaction that rolls back afterwards,
 * so the tests do not have to clean up after each other.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Auditing lives in its own configuration rather than on the application class, so a slice has to
// ask for it. Without this, created_at is never populated and every insert violates NOT NULL.
@Import(JpaAuditingConfig.class)
class TransactionRepositoryTest extends PostgresTestBase {

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.save(Fixtures.customer());
        alice = accountRepository.save(Fixtures.account(customer, "ACC-A", "100000.00"));
        bob = accountRepository.save(Fixtures.account(customer, "ACC-B", "100000.00"));
    }

    @Test
    @DisplayName("the idempotency key is unique, which is what makes a retry safe")
    void duplicateIdempotencyKeyIsRejected() {
        transactionRepository.saveAndFlush(
                Fixtures.transaction(alice, bob, TODAY).idempotencyKey("same-key").build());

        // saveAndFlush, not save: the constraint is enforced at flush, and without forcing it the
        // violation would surface at commit - after the assertion had already passed.
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(
                Fixtures.transaction(alice, bob, TODAY).idempotencyKey("same-key").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("many transactions may omit the key - Postgres allows repeated NULLs")
    void absentIdempotencyKeysDoNotCollide() {
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY).build());

        assertThat(transactionRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("accountNumber matches both sides of a transfer")
    void accountNumberFilterMatchesSentAndReceived() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY).build());   // alice sends
        transactionRepository.save(Fixtures.transaction(bob, alice, TODAY).build());   // alice receives
        transactionRepository.saveAndFlush(Fixtures.transaction(bob, bob, TODAY).build()); // neither

        List<Transaction> found = transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, "ACC-A", null, null));

        // An account's history is everything it sent and everything it received. An earlier version
        // walked the entity relations here, which emitted two inner joins inside one OR and
        // silently changed which rows came back.
        assertThat(found).hasSize(2);
    }

    @Test
    void statusFilter() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY)
                .status(TransactionStatus.INSUFFICIENT_FUND).build());

        assertThat(transactionRepository.findAll(TransactionSpecifications.withFilters(
                TransactionStatus.INSUFFICIENT_FUND, null, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("the date range is inclusive at both ends")
    void dateRangeFilterIsInclusive() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY.minusDays(2)).build());
        transactionRepository.save(Fixtures.transaction(alice, bob, YESTERDAY).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY).build());

        assertThat(transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, null, YESTERDAY, TODAY))).hasSize(2);
        assertThat(transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, null, TODAY, TODAY))).hasSize(1);
    }

    @Test
    void filtersCombine() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, YESTERDAY)
                .status(TransactionStatus.FAILED).build());

        assertThat(transactionRepository.findAll(TransactionSpecifications.withFilters(
                TransactionStatus.FAILED, "ACC-A", YESTERDAY, YESTERDAY))).hasSize(1);
    }

    @Test
    @DisplayName("no filters returns everything rather than nothing")
    void absentFiltersMatchAll() {
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY).build());

        assertThat(transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, null, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("the day is aggregated in the database, bucketed by status")
    void summariseByStatus() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY)
                .amount(new BigDecimal("1000.00")).transactionFee(new BigDecimal("5.00"))
                .commissionWorthy(true).commission(new BigDecimal("1.00")).build());
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY)
                .amount(new BigDecimal("2000.00")).transactionFee(new BigDecimal("10.00"))
                .commissionWorthy(true).commission(new BigDecimal("2.00")).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY)
                .status(TransactionStatus.INSUFFICIENT_FUND)
                .amount(new BigDecimal("9000.00")).transactionFee(new BigDecimal("45.00"))
                .commissionWorthy(false).commission(BigDecimal.ZERO).build());

        Map<TransactionStatus, StatusAggregate> buckets = transactionRepository
                .summariseByStatus(TODAY).stream()
                .collect(Collectors.toMap(StatusAggregate::status, Function.identity()));

        StatusAggregate successful = buckets.get(TransactionStatus.SUCCESSFUL);
        assertThat(successful.count()).isEqualTo(2);
        assertThat(successful.totalAmount()).isEqualByComparingTo("3000.00");
        assertThat(successful.totalFees()).isEqualByComparingTo("15.00");
        assertThat(successful.totalCommission()).isEqualByComparingTo("3.00");
        assertThat(successful.commissionWorthyCount()).isEqualTo(2);

        // The rejected transfer carries an assessed fee of 45.00 that was never charged. It sits in
        // its own bucket so the summary can leave it out of collected revenue.
        StatusAggregate rejected = buckets.get(TransactionStatus.INSUFFICIENT_FUND);
        assertThat(rejected.count()).isEqualTo(1);
        assertThat(rejected.totalFees()).isEqualByComparingTo("45.00");
    }

    @Test
    void summariseAnEmptyDayReturnsNoBuckets() {
        assertThat(transactionRepository.summariseByStatus(TODAY)).isEmpty();
    }

    @Test
    @DisplayName("the backlog query finds past days still holding unassessed transactions")
    void findDatesWithUnassessedTransactions() {
        // Two past days never assessed, one past day already done, and today - which is not closed.
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY.minusDays(3)).build());
        transactionRepository.save(Fixtures.transaction(alice, bob, YESTERDAY).build());
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY.minusDays(2))
                .commissionWorthy(true).commission(new BigDecimal("1.00")).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY).build());

        List<LocalDate> dates = transactionRepository
                .findDatesWithUnassessedTransactions(TODAY, Pageable.unpaged());

        // Most recent first, the already-assessed day absent, and today excluded because a day
        // still in progress is not ready to be assessed.
        assertThat(dates).containsExactly(YESTERDAY, TODAY.minusDays(3));
    }

    @Test
    @DisplayName("the unassessed page is bounded, so a busy day is never loaded whole")
    void findUnassessedRespectsThePageSize() {
        for (int i = 0; i < 5; i++) {
            transactionRepository.save(Fixtures.transaction(alice, bob, YESTERDAY).build());
        }
        transactionRepository.flush();

        assertThat(transactionRepository.findUnassessed(YESTERDAY, PageRequest.ofSize(2))).hasSize(2);
        assertThat(transactionRepository.findUnassessed(YESTERDAY, PageRequest.ofSize(50))).hasSize(5);
    }

    @Test
    void findUnassessedSkipsRowsAlreadyAssessed() {
        transactionRepository.save(Fixtures.transaction(alice, bob, YESTERDAY).build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, YESTERDAY)
                .commissionWorthy(false).commission(BigDecimal.ZERO).build());

        assertThat(transactionRepository.findUnassessed(YESTERDAY, PageRequest.ofSize(50))).hasSize(1);
    }

    @Test
    void lookupByReferenceAndByIdempotencyKey() {
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY)
                .transactionReference("DOT-LOOKUP").idempotencyKey("key-lookup").build());

        assertThat(transactionRepository.findByTransactionReference("DOT-LOOKUP")).isPresent();
        assertThat(transactionRepository.findByIdempotencyKey("key-lookup")).isPresent();
        assertThat(transactionRepository.findByTransactionReference("nope")).isEmpty();
    }

    @Test
    @DisplayName("listing is newest first")
    void ordering() {
        transactionRepository.save(Fixtures.transaction(alice, bob, TODAY.minusDays(1))
                .transactionReference("OLDER").build());
        transactionRepository.saveAndFlush(Fixtures.transaction(alice, bob, TODAY)
                .transactionReference("NEWER").build());

        List<Transaction> page = transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        assertThat(page.getFirst().getTransactionReference()).isEqualTo("NEWER");
    }
}

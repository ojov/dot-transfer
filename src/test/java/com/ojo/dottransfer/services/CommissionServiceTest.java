package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.responses.CommissionRunResponse;
import com.ojo.dottransfer.repositories.AccountRepository;
import com.ojo.dottransfer.repositories.CustomerRepository;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.repositories.specification.TransactionSpecifications;
import com.ojo.dottransfer.support.Fixtures;
import com.ojo.dottransfer.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly commission run, end to end against the database. Integrated rather than mocked
 * because the behaviour that matters - per-page commits, and a claim query whose result set shrinks
 * as the run progresses - only exists once real transactions are involved.
 *
 * <p>{@code batch-size=2} forces the paging loop to run several times over a handful of rows, so
 * the multi-page path is exercised without inserting hundreds of records.
 */
@SpringBootTest(properties = "dot.jobs.batch-size=2")
@ActiveProfiles("test")
class CommissionServiceTest extends PostgresTestBase {

    @Autowired
    private CommissionService commissionService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private BusinessClock businessClock;

    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        Customer customer = customerRepository.save(Fixtures.customer());
        source = accountRepository.save(Fixtures.account(customer, "C-" + shortId(), "100000.00"));
        destination = accountRepository.save(Fixtures.account(customer, "C-" + shortId(), "100000.00"));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * A distinct closed day per test, so tests cannot see each other's rows. Derived from today
     * rather than a fixed date: a hard-coded base plus an offset drifts into the future as the
     * calendar moves, and a future day is excluded from the backlog by design.
     */
    private LocalDate isolatedDay() {
        return businessClock.today().minusDays(30 + Math.abs(UUID.randomUUID().hashCode() % 2000));
    }

    private Transaction save(LocalDate day, TransactionStatus status, String fee) {
        return transactionRepository.save(Fixtures.transaction(source, destination, day)
                .transactionReference("REF-" + shortId())
                .status(status)
                .transactionFee(new BigDecimal(fee))
                .build());
    }

    @Test
    @DisplayName("commission is 20% of the fee, on successful transactions only")
    void assignsCommissionToSuccessfulTransactions() {
        LocalDate day = isolatedDay();
        save(day, TransactionStatus.SUCCESSFUL, "100.00");
        save(day, TransactionStatus.SUCCESSFUL, "5.00");
        save(day, TransactionStatus.INSUFFICIENT_FUND, "25.00");
        save(day, TransactionStatus.FAILED, "10.00");

        CommissionRunResponse result = commissionService.assessDay(day);

        assertThat(result.assessed()).isEqualTo(4);
        assertThat(result.commissionWorthy()).isEqualTo(2);
        assertThat(result.totalCommission()).isEqualByComparingTo("21.00");   // 20.00 + 1.00
        assertThat(result.dates()).containsExactly(day);
    }

    @Test
    @DisplayName("every row is marked, so null keeps meaning 'not yet assessed'")
    void nonSuccessfulTransactionsAreMarkedNotWorthyRatherThanLeftNull() {
        LocalDate day = isolatedDay();
        save(day, TransactionStatus.INSUFFICIENT_FUND, "25.00");

        commissionService.assessDay(day);

        assertThat(transactionRepository.findUnassessed(day, Pageable.ofSize(10)))
                .as("nothing should be left unassessed")
                .isEmpty();

        assertThat(transactionRepository.findAll(
                TransactionSpecifications.withFilters(null, null, day, day)))
                .allSatisfy(t -> {
                    assertThat(t.getCommissionWorthy()).isFalse();
                    assertThat(t.getCommission()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(t.getCommissionComputedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("re-running a day changes nothing")
    void reRunIsANoOp() {
        LocalDate day = isolatedDay();
        save(day, TransactionStatus.SUCCESSFUL, "100.00");

        commissionService.assessDay(day);
        CommissionRunResponse second = commissionService.assessDay(day);

        // The null sentinel is what makes this safe: assessed rows drop out of the claim query, so
        // a repeat run - after a crash, or a nervous operator - cannot double-count commission.
        assertThat(second.assessed()).isZero();
        assertThat(second.totalCommission()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("more transactions than one page are all assessed")
    void pagingCoversEveryTransaction() {
        LocalDate day = isolatedDay();
        for (int i = 0; i < 7; i++) {
            save(day, TransactionStatus.SUCCESSFUL, "10.00");
        }

        // batch-size is 2, so this needs four passes. The loop asks for the first page each time
        // rather than walking offsets - an offset walk would skip rows as assessed ones leave the
        // result set.
        CommissionRunResponse result = commissionService.assessDay(day);

        assertThat(result.assessed()).isEqualTo(7);
        assertThat(result.totalCommission()).isEqualByComparingTo("14.00");   // 7 x 2.00
        assertThat(transactionRepository.findUnassessed(day, Pageable.ofSize(10))).isEmpty();
    }

    @Test
    @DisplayName("the backlog picks up days missed during an outage, most recent first")
    void backlogClearsEveryMissedDay() {
        LocalDate older = isolatedDay();
        LocalDate newer = older.plusDays(1);
        save(older, TransactionStatus.SUCCESSFUL, "100.00");
        save(newer, TransactionStatus.SUCCESSFUL, "50.00");

        CommissionRunResponse result = commissionService.assessBacklog();

        // A job that only ever looked at yesterday would leave `older` unassessed forever.
        assertThat(result.dates()).contains(newer, older);
        assertThat(result.dates().indexOf(newer)).isLessThan(result.dates().indexOf(older));
        assertThat(transactionRepository.findUnassessed(older, Pageable.ofSize(10))).isEmpty();
        assertThat(transactionRepository.findUnassessed(newer, Pageable.ofSize(10))).isEmpty();
    }

    @Test
    @DisplayName("today is left alone - the day is not finished yet")
    void backlogExcludesTheCurrentDay() {
        LocalDate today = businessClock.today();
        Transaction untouched = save(today, TransactionStatus.SUCCESSFUL, "100.00");

        List<LocalDate> dates = commissionService.assessBacklog().dates();

        assertThat(dates).doesNotContain(today);
        // Asserted on this test's own row rather than on a count for the day: other tests in the
        // suite write transactions dated today too.
        assertThat(transactionRepository.findById(untouched.getId()).orElseThrow().getCommissionWorthy())
                .isNull();
    }

    @Test
    void backlogWithNothingOutstandingReportsNothing() {
        commissionService.assessBacklog();   // clear whatever previous tests left

        CommissionRunResponse result = commissionService.assessBacklog();

        assertThat(result.dates()).isEmpty();
        assertThat(result.assessed()).isZero();
    }
}

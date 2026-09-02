package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.responses.DailySummaryResponse;
import com.ojo.dottransfer.repositories.AccountRepository;
import com.ojo.dottransfer.repositories.CustomerRepository;
import com.ojo.dottransfer.repositories.DailyTransactionSummaryRepository;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.support.Fixtures;
import com.ojo.dottransfer.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DailySummaryServiceTest extends PostgresTestBase {

    @Autowired
    private DailySummaryService dailySummaryService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private DailyTransactionSummaryRepository summaryRepository;
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
        source = accountRepository.save(Fixtures.account(customer, "S-" + shortId(), "100000.00"));
        destination = accountRepository.save(Fixtures.account(customer, "D-" + shortId(), "100000.00"));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * A distinct closed day per test. Derived from today rather than a fixed base date, which
     * would drift into the future over time and be rejected as un-summarisable.
     */
    private LocalDate isolatedPastDay() {
        return businessClock.today().minusDays(30 + Math.abs(UUID.randomUUID().hashCode() % 2000));
    }

    private void save(LocalDate day, TransactionStatus status, String amount, String fee, String commission) {
        transactionRepository.save(Fixtures.transaction(source, destination, day)
                .transactionReference("REF-" + shortId())
                .status(status)
                .amount(new BigDecimal(amount))
                .transactionFee(new BigDecimal(fee))
                .billedAmount(new BigDecimal(amount).add(new BigDecimal(fee)))
                .commissionWorthy(commission != null)
                .commission(commission == null ? BigDecimal.ZERO : new BigDecimal(commission))
                .build());
    }

    @Test
    @DisplayName("fees and commission count only successful transactions")
    void rejectedTransfersDoNotContributeRevenue() {
        LocalDate day = isolatedPastDay();
        save(day, TransactionStatus.SUCCESSFUL, "1000.00", "5.00", "1.00");
        save(day, TransactionStatus.SUCCESSFUL, "2000.00", "10.00", "2.00");
        // Assessed a 45.00 fee it was never charged, because no money moved.
        save(day, TransactionStatus.INSUFFICIENT_FUND, "9000.00", "45.00", null);

        DailySummaryResponse summary = dailySummaryService.getSummary(day);

        assertThat(summary.totalCount()).isEqualTo(3);
        assertThat(summary.successfulCount()).isEqualTo(2);
        assertThat(summary.insufficientFundCount()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualByComparingTo("12000.00");   // every attempt
        assertThat(summary.successfulAmount()).isEqualByComparingTo("3000.00");
        assertThat(summary.totalFees()).isEqualByComparingTo("15.00");        // not 60.00
        assertThat(summary.totalCommission()).isEqualByComparingTo("3.00");
        assertThat(summary.commissionWorthyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("reading a summary never writes one")
    void readingDoesNotPersistASnapshot() {
        LocalDate day = isolatedPastDay();
        save(day, TransactionStatus.SUCCESSFUL, "1000.00", "5.00", "1.00");

        DailySummaryResponse summary = dailySummaryService.getSummary(day);

        // The regression this guards: when a read also cached its result, two concurrent reads of
        // the same un-snapshotted day both inserted and the unique constraint failed one of them,
        // turning a plain GET into a conflict.
        assertThat(summary.fromSnapshot()).isFalse();
        assertThat(summaryRepository.findBySummaryDate(day)).isEmpty();
    }

    @Test
    @DisplayName("the current day is provisional because it can still change")
    void todayIsProvisional() {
        LocalDate today = businessClock.today();

        DailySummaryResponse summary = dailySummaryService.getSummary(today);

        assertThat(summary.provisional()).isTrue();
        assertThat(summary.fromSnapshot()).isFalse();
        assertThat(summaryRepository.findBySummaryDate(today)).isEmpty();
    }

    @Test
    @DisplayName("once the job has snapshotted a closed day, reads are served from it")
    void snapshotIsServedOnceGenerated() {
        LocalDate day = isolatedPastDay();
        save(day, TransactionStatus.SUCCESSFUL, "1000.00", "5.00", "1.00");

        assertThat(dailySummaryService.getSummary(day).fromSnapshot()).isFalse();

        dailySummaryService.generateSnapshot(day);

        DailySummaryResponse served = dailySummaryService.getSummary(day);
        assertThat(served.fromSnapshot()).isTrue();
        assertThat(served.provisional()).isFalse();
        assertThat(served.totalFees()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("re-running the snapshot updates the day in place")
    void regeneratingUpdatesRatherThanDuplicates() {
        LocalDate day = isolatedPastDay();
        save(day, TransactionStatus.SUCCESSFUL, "1000.00", "5.00", "1.00");
        dailySummaryService.generateSnapshot(day);

        // A late-arriving transaction, then a regeneration - which is exactly what re-running the
        // commission job for a day would be followed by.
        save(day, TransactionStatus.SUCCESSFUL, "3000.00", "15.00", "3.00");
        DailySummaryResponse regenerated = dailySummaryService.generateSnapshot(day);

        assertThat(regenerated.totalCount()).isEqualTo(2);
        assertThat(regenerated.totalFees()).isEqualByComparingTo("20.00");
        // findBySummaryDate returns an Optional, so a second row for the day could not be
        // represented - the unique constraint on summary_date is what guarantees that.
        assertThat(summaryRepository.findBySummaryDate(day))
                .get()
                .satisfies(stored -> assertThat(stored.getTotalCount()).isEqualTo(2));
    }

    @Test
    void aDayWithNoTransactionsSummarisesToZeroRatherThanFailing() {
        DailySummaryResponse summary = dailySummaryService.getSummary(isolatedPastDay());

        assertThat(summary.totalCount()).isZero();
        assertThat(summary.totalAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.totalCommission()).isEqualByComparingTo("0.00");
    }

    @Test
    void futureDatesAreRejected() {
        assertThatThrownBy(() -> dailySummaryService.getSummary(LocalDate.now().plusDays(1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("future");
    }
}

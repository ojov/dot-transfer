package com.ojo.dottransfer.services;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.requests.TransferRequest;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.repositories.AccountRepository;
import com.ojo.dottransfer.repositories.CustomerRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests the whole locking design exists for.
 *
 * <p>Nothing here is mocked and nothing is wrapped in a rolling-back transaction: real threads,
 * real committed transactions, real {@code SELECT ... FOR UPDATE} against a real PostgreSQL. A lost
 * update only appears when two transactions genuinely interleave, so anything less would assert
 * that the code compiles rather than that it is correct.
 *
 * <p>The {@code test} profile keeps the dev seeder out; each test creates its own accounts with
 * unique numbers, so they cannot interfere with one another.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferConcurrencyTest extends PostgresTestBase {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(Fixtures.customer());
    }

    private Account account(String balance) {
        return accountRepository.save(
                Fixtures.account(customer, "ACC-" + UUID.randomUUID().toString().substring(0, 8), balance));
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    /** Runs every task at once, as close to simultaneously as the JVM allows. */
    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();   // hold every thread until all are ready, maximising contention
                return task.call();
            }));
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    @Test
    @DisplayName("concurrent transfers cannot overdraw an account")
    void concurrentTransfersCannotOverdraw() throws Exception {
        Account source = account("1000.00");
        Account destination = account("0.00");

        // 100.00 costs 100.50 with the fee the sender bears, so exactly 9 of these fit inside
        // 1000.00 (904.50). A tenth would need 1005.00. Without row locking, several threads would
        // read the same balance and all believe they could afford it.
        List<TransactionResponse> results = runConcurrently(30, () ->
                transferService.transfer(
                        new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(),
                                new BigDecimal("100.00"), "race"),
                        null));

        long successful = results.stream().filter(r -> r.status() == TransactionStatus.SUCCESSFUL).count();
        long rejected = results.stream().filter(r -> r.status() == TransactionStatus.INSUFFICIENT_FUND).count();

        assertThat(successful).isEqualTo(9);
        assertThat(rejected).isEqualTo(21);

        // The arithmetic has to close exactly: 1000.00 - (9 x 100.50) = 95.50.
        assertThat(balanceOf(source)).isEqualByComparingTo("95.50");
        // The recipient receives the amount, not the billed amount - the fee leaves the system.
        assertThat(balanceOf(destination)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(source)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("opposing transfers between the same two accounts do not deadlock")
    void opposingTransfersDoNotDeadlock() throws Exception {
        Account a = account("100000.00");
        Account b = account("100000.00");
        AtomicInteger counter = new AtomicInteger();

        // Half the threads send A->B and half send B->A, simultaneously. Locking in the order the
        // transfer happens to name the accounts would have each direction holding the row the
        // other needs; Postgres would detect the cycle and abort transactions, which this service
        // records as FAILED. Locking by account number instead means both directions queue in the
        // same order. Zero failures is the assertion that the ordering works.
        List<TransactionResponse> results = runConcurrently(20, () -> {
            boolean forward = counter.getAndIncrement() % 2 == 0;
            Account from = forward ? a : b;
            Account to = forward ? b : a;
            return transferService.transfer(
                    new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                            new BigDecimal("100.00"), "deadlock-probe"),
                    null);
        });

        assertThat(results).hasSize(20);
        assertThat(results).noneMatch(r -> r.status() == TransactionStatus.FAILED);
        assertThat(results).allMatch(r -> r.status() == TransactionStatus.SUCCESSFUL);

        // 10 transfers each way: every account paid 10 x 100.50 and received 10 x 100.00,
        // so each is down exactly the fees it paid.
        assertThat(balanceOf(a)).isEqualByComparingTo("99995.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("99995.00");
    }

    @Test
    @DisplayName("the same idempotency key used concurrently debits exactly once")
    void concurrentReplaysOfOneKeyDebitOnce() throws Exception {
        Account source = account("100000.00");
        Account destination = account("0.00");
        String key = "concurrent-key-" + UUID.randomUUID();

        // This is the cross-instance retry, simulated in one JVM: several callers sending the same
        // request with the same key at the same moment. The unique constraint lets one insert
        // through and the rest resolve to its row.
        List<TransactionResponse> results = runConcurrently(10, () ->
                transferService.transfer(
                        new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(),
                                new BigDecimal("500.00"), "idempotent"),
                        key));

        assertThat(results).extracting(TransactionResponse::transactionReference)
                .containsOnly(results.getFirst().transactionReference());
        assertThat(transactionRepository.findByIdempotencyKey(key)).isPresent();

        // 500.00 plus a 2.50 fee, charged once however many times it was asked for.
        assertThat(balanceOf(source)).isEqualByComparingTo("99497.50");
        assertThat(balanceOf(destination)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("money is conserved: what leaves the source arrives or stays put")
    void moneyIsNeverCreatedOrDestroyed() throws Exception {
        Account source = account("50000.00");
        Account destination = account("50000.00");
        BigDecimal openingTotal = balanceOf(source).add(balanceOf(destination));

        List<TransactionResponse> results = runConcurrently(25, () ->
                transferService.transfer(
                        new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(),
                                new BigDecimal("137.53"), "conservation"),
                        null));

        BigDecimal feesCollected = results.stream()
                .filter(r -> r.status() == TransactionStatus.SUCCESSFUL)
                .map(TransactionResponse::transactionFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The only value that may leave the pair is the fees, which the platform takes. Any other
        // discrepancy would be a lost or duplicated update.
        assertThat(balanceOf(source).add(balanceOf(destination)))
                .isEqualByComparingTo(openingTotal.subtract(feesCollected));
    }
}

package com.ojo.dottransfer.services;

import com.ojo.dottransfer.enums.AccountStatus;
import com.ojo.dottransfer.exception.AccountNotActiveException;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.requests.TransferRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules {@code TransferExecutor} enforces before it will move anything.
 *
 * <p>These were previously untested from either side: the controller slice mocks
 * {@code TransferService} away entirely, and the concurrency tests only exercise the happy path and
 * the insufficient-funds path. So the checks that stop a transfer happening at all had no coverage.
 *
 * <p>Every case here must leave no transaction record behind. That is the distinction the design
 * rests on: a rejected <em>request</em> is an error and writes nothing, while a rejected
 * <em>transfer</em> - insufficient funds - is an outcome and is recorded.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransferValidationTest extends PostgresTestBase {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private Customer customer;
    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(Fixtures.customer());
        source = account("100000.00", AccountStatus.ACTIVE, "NGN");
        destination = account("100000.00", AccountStatus.ACTIVE, "NGN");
    }

    private Account account(String balance, AccountStatus status, String currency) {
        Account account = Fixtures.account(customer,
                "V-" + UUID.randomUUID().toString().substring(0, 8), balance, status);
        account.setCurrency(currency);
        return accountRepository.save(account);
    }

    private TransferRequest transfer(String from, String to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), "validation");
    }

    private long transactionCount() {
        return transactionRepository.count();
    }

    @Test
    @DisplayName("an account cannot transfer to itself")
    void selfTransferIsRejected() {
        long before = transactionCount();

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), source.getAccountNumber(), "100.00"), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("must be different");

        // Rejected before either row is locked, so nothing is recorded.
        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a non-positive amount is refused by the service, not just by bean validation")
    void nonPositiveAmountIsRejected() {
        long before = transactionCount();

        // The controller's @DecimalMin would normally catch this. The service re-checks because it
        // is the only path that moves money, and any other caller would bypass that validation.
        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), destination.getAccountNumber(), "0.00"), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("greater than zero");

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), destination.getAccountNumber(), "-100.00"), null))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a frozen source cannot send")
    void frozenSourceIsRejected() {
        Account frozen = account("100000.00", AccountStatus.FROZEN, "NGN");
        long before = transactionCount();

        assertThatThrownBy(() -> transferService.transfer(
                transfer(frozen.getAccountNumber(), destination.getAccountNumber(), "100.00"), null))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessageContaining("FROZEN");

        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a closed destination cannot receive")
    void closedDestinationIsRejected() {
        Account closed = account("100000.00", AccountStatus.CLOSED, "NGN");

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), closed.getAccountNumber(), "100.00"), null))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("currencies must match - this service does not convert")
    void currencyMismatchIsRejected() {
        Account usd = account("100000.00", AccountStatus.ACTIVE, "USD");

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), usd.getAccountNumber(), "100.00"), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("different currencies");
    }

    @Test
    void unknownAccountsAreReportedAsNotFound() {
        assertThatThrownBy(() -> transferService.transfer(
                transfer("DOES-NOT-EXIST", destination.getAccountNumber(), "100.00"), null))
                .isInstanceOf(EntityNotFoundException.class);

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), "DOES-NOT-EXIST", "100.00"), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("a rejected request leaves both balances untouched")
    void balancesAreUnchangedByARejectedRequest() {
        BigDecimal sourceOpening = source.getBalance();
        BigDecimal destinationOpening = destination.getBalance();

        assertThatThrownBy(() -> transferService.transfer(
                transfer(source.getAccountNumber(), source.getAccountNumber(), "100.00"), null))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(sourceOpening);
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(destinationOpening);
    }
}

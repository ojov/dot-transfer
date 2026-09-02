package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.config.properties.FeeProperties;
import com.ojo.dottransfer.enums.AccountStatus;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.AccountNotActiveException;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.requests.TransferRequest;
import com.ojo.dottransfer.repositories.AccountRepository;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.utils.FeeCalculator;
import com.ojo.dottransfer.utils.MoneyUtil;
import com.ojo.dottransfer.utils.ReferenceGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The transactional half of the transfer path, kept separate from {@link TransferService} so
 * Spring's proxy actually applies these boundaries - a {@code @Transactional} method called from
 * inside the same bean bypasses the proxy and silently runs without a transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FeeProperties feeProperties;
    private final BusinessClock businessClock;

    /**
     * Moves the money and records the outcome, in one transaction.
     *
     * <p>An insufficient balance is a business <em>outcome</em>, not an error: this method returns
     * a persisted INSUFFICIENT_FUND row rather than throwing. Throwing would roll back the very
     * record the caller needs to keep. Only genuine request errors - unknown account, inactive
     * account, nonsensical request - throw, and nothing has been written at that point.
     */
    @Transactional
    public Transaction execute(TransferRequest request, String idempotencyKey) {
        String sourceNumber = request.sourceAccountNumber();
        String destinationNumber = request.destinationAccountNumber();

        if (sourceNumber.equals(destinationNumber)) {
            throw new InvalidRequestException("Source and destination accounts must be different");
        }

        // Both rows are locked before either balance is read. The lock order is by account number,
        // never by the direction of the transfer, so A->B and a simultaneous B->A queue behind each
        // other instead of each holding the row the other needs.
        Account source;
        Account destination;
        if (sourceNumber.compareTo(destinationNumber) < 0) {
            source = lock(sourceNumber);
            destination = lock(destinationNumber);
        } else {
            destination = lock(destinationNumber);
            source = lock(sourceNumber);
        }

        requireActive(source);
        requireActive(destination);

        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new InvalidRequestException("Cannot transfer between accounts in different currencies");
        }

        BigDecimal amount = MoneyUtil.normalize(request.amount());
        BigDecimal fee = FeeCalculator.computeFee(amount, feeProperties);
        BigDecimal billedAmount = FeeCalculator.computeBilledAmount(amount, fee);
        LocalDate businessDate = businessClock.today();

        Transaction transaction = Transaction.builder()
                .transactionReference(ReferenceGenerator.generate(businessDate))
                .idempotencyKey(idempotencyKey)
                .transactionDate(businessDate)
                .sourceAccount(source)
                .destinationAccount(destination)
                .sourceAccountNumber(sourceNumber)
                .destinationAccountNumber(destinationNumber)
                .amount(amount)
                .transactionFee(fee)
                .billedAmount(billedAmount)
                .currency(source.getCurrency())
                .description(request.description())
                .build();

        // The sender bears the fee, so the balance has to cover amount + fee, not just the amount.
        if (MoneyUtil.covers(source.getBalance(), billedAmount)) {
            source.setBalance(MoneyUtil.normalize(source.getBalance().subtract(billedAmount)));
            destination.setBalance(MoneyUtil.normalize(destination.getBalance().add(amount)));
            transaction.setStatus(TransactionStatus.SUCCESSFUL);
            transaction.setStatusMessage("Transfer completed");
        } else {
            transaction.setStatus(TransactionStatus.INSUFFICIENT_FUND);
            transaction.setStatusMessage("Balance %s is below the required %s"
                    .formatted(source.getBalance(), billedAmount));
        }

        Transaction saved = transactionRepository.save(transaction);
        log.info("Transfer {} {} from {} to {} for {} (fee {})", saved.getTransactionReference(),
                saved.getStatus(), sourceNumber, destinationNumber, amount, fee);
        return saved;
    }

    /**
     * Records a FAILED row for a transfer that blew up unexpectedly.
     *
     * <p>{@code REQUIRES_NEW} is the point: the failing transaction is being rolled back, so this
     * row has to be written on a separate connection or it would roll back with it and the failure
     * would leave no trace. Accounts are read without a lock - nothing is being mutated.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction recordFailure(TransferRequest request, String idempotencyKey, String reason) {
        Account source = accountRepository.findByAccountNumber(request.sourceAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account %s not found".formatted(request.sourceAccountNumber())));
        Account destination = accountRepository.findByAccountNumber(request.destinationAccountNumber())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account %s not found".formatted(request.destinationAccountNumber())));

        BigDecimal amount = MoneyUtil.normalize(request.amount());
        BigDecimal fee = FeeCalculator.computeFee(amount, feeProperties);
        LocalDate businessDate = businessClock.today();

        return transactionRepository.save(Transaction.builder()
                .transactionReference(ReferenceGenerator.generate(businessDate))
                .idempotencyKey(idempotencyKey)
                .transactionDate(businessDate)
                .sourceAccount(source)
                .destinationAccount(destination)
                .sourceAccountNumber(source.getAccountNumber())
                .destinationAccountNumber(destination.getAccountNumber())
                .amount(amount)
                .transactionFee(fee)
                .billedAmount(FeeCalculator.computeBilledAmount(amount, fee))
                .currency(source.getCurrency())
                .description(request.description())
                .status(TransactionStatus.FAILED)
                .statusMessage(reason)
                .build());
    }

    private Account lock(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account %s not found".formatted(accountNumber)));
    }

    private void requireActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("Account %s is %s and cannot transact"
                    .formatted(account.getAccountNumber(), account.getStatus()));
        }
    }
}

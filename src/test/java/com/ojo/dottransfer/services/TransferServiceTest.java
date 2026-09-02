package com.ojo.dottransfer.services;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.AccountNotActiveException;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Account;
import com.ojo.dottransfer.models.entities.Customer;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.requests.TransferRequest;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The orchestration around a transfer - idempotency, the cross-instance insert race, and failure
 * recording. Mocked rather than integrated on purpose: these branches are triggered by events
 * (a unique-constraint collision, a mid-transfer crash) that are awkward to provoke on demand
 * against a live database, and none of them depend on money actually moving.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferExecutor transferExecutor;
    @Mock
    private TransactionRepository transactionRepository;
    @InjectMocks
    private TransferService transferService;

    private TransferRequest request;
    private Transaction stored;

    @BeforeEach
    void setUp() {
        request = new TransferRequest("ACC-A", "ACC-B", new BigDecimal("1000.00"), "Rent");

        Customer customer = Fixtures.customer();
        Account source = Fixtures.account(customer, "ACC-A", "50000.00");
        Account destination = Fixtures.account(customer, "ACC-B", "50000.00");
        stored = Fixtures.transaction(source, destination, LocalDate.of(2026, 9, 2))
                .transactionReference("DOT-ORIGINAL")
                .build();
    }

    @Test
    @DisplayName("a replayed key returns the original transaction without transferring again")
    void replayReturnsTheStoredTransaction() {
        given(transactionRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(stored));

        TransactionResponse result = transferService.transfer(request, "key-1");

        assertThat(result.transactionReference()).isEqualTo("DOT-ORIGINAL");
        // The critical assertion: the money path was never entered a second time.
        verify(transferExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("two instances racing on the same key: the loser returns the winner's row")
    void insertRaceResolvesToTheWinningTransaction() {
        // Both instances missed the initial lookup, both attempted the insert, and the unique
        // constraint let exactly one through. This is the loser.
        given(transactionRepository.findByIdempotencyKey("key-1"))
                .willReturn(Optional.empty())      // the lookup before attempting
                .willReturn(Optional.of(stored));  // the re-read after losing
        willThrow(new DataIntegrityViolationException("duplicate key"))
                .given(transferExecutor).execute(any(), any());

        TransactionResponse result = transferService.transfer(request, "key-1");

        assertThat(result.transactionReference()).isEqualTo("DOT-ORIGINAL");
    }

    @Test
    @DisplayName("a constraint violation that is not the idempotency race is not swallowed")
    void unrelatedConstraintViolationPropagates() {
        given(transactionRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        willThrow(new DataIntegrityViolationException("some other constraint"))
                .given(transferExecutor).execute(any(), any());

        assertThatThrownBy(() -> transferService.transfer(request, "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void constraintViolationWithNoKeyAtAllPropagates() {
        willThrow(new DataIntegrityViolationException("duplicate reference"))
                .given(transferExecutor).execute(any(), any());

        assertThatThrownBy(() -> transferService.transfer(request, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unexpected failure is recorded as a FAILED transaction rather than vanishing")
    void unexpectedFailureIsRecorded() {
        willThrow(new IllegalStateException("connection reset"))
                .given(transferExecutor).execute(any(), any());
        given(transferExecutor.recordFailure(any(), any(), any()))
                .willReturn(Fixtures.transaction(stored.getSourceAccount(), stored.getDestinationAccount(),
                        LocalDate.of(2026, 9, 2))
                        .status(TransactionStatus.FAILED)
                        .statusMessage("Transfer could not be completed due to an internal error")
                        .build());

        TransactionResponse result = transferService.transfer(request, null);

        assertThat(result.status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    @DisplayName("if recording the failure also fails, the original cause is what surfaces")
    void bookkeepingFailureDoesNotMaskTheRealCause() {
        willThrow(new IllegalStateException("connection reset"))
                .given(transferExecutor).execute(any(), any());
        willThrow(new EntityNotFoundException("Account ACC-A not found"))
                .given(transferExecutor).recordFailure(any(), any(), any());

        // Letting the bookkeeping exception escape would report a 404 for what was really an
        // infrastructure failure - the wrong problem, and a misleading one.
        assertThatThrownBy(() -> transferService.transfer(request, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("connection reset");
    }

    @Test
    @DisplayName("request errors propagate untouched - there is no transfer to record")
    void requestErrorsAreNotRecordedAsFailedTransfers() {
        willThrow(new AccountNotActiveException("Account ACC-B is FROZEN and cannot transact"))
                .given(transferExecutor).execute(any(), any());

        assertThatThrownBy(() -> transferService.transfer(request, null))
                .isInstanceOf(AccountNotActiveException.class);
        verify(transferExecutor, never()).recordFailure(any(), any(), any());
    }

    @Test
    void anOverlongIdempotencyKeyIsRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> transferService.transfer(request, "x".repeat(65)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 64");
        verify(transferExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a blank key is treated as no key, not as a key that happens to be empty")
    void blankKeyIsTreatedAsAbsent() {
        given(transferExecutor.execute(any(), any())).willReturn(stored);

        transferService.transfer(request, "   ");

        verify(transferExecutor).execute(any(), isNull());
        verify(transactionRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void surroundingWhitespaceIsTrimmedFromTheKey() {
        given(transactionRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(transferExecutor.execute(any(), any())).willReturn(stored);

        transferService.transfer(request, "  key-1  ");

        verify(transferExecutor).execute(any(), eq("key-1"));
    }
}

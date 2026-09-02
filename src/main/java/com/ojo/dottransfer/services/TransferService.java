package com.ojo.dottransfer.services;

import com.ojo.dottransfer.exception.AccountNotActiveException;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.requests.TransferRequest;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.utils.Mapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Orchestrates a transfer: idempotency, delegation, and failure recording.
 *
 * <p>Deliberately not {@code @Transactional} itself. It has to survive the rollback of the work it
 * delegates, so that it can still write a FAILED record afterwards - which it could not do from
 * inside the transaction that just failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final TransferExecutor transferExecutor;
    private final TransactionRepository transactionRepository;

    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        String key = normaliseKey(idempotencyKey);

        Optional<Transaction> replayed = findByKey(key);
        if (replayed.isPresent()) {
            log.info("Idempotency key {} replayed; returning existing transaction {}",
                    key, replayed.get().getTransactionReference());
            return Mapper.toResponse(replayed.get());
        }

        try {
            return Mapper.toResponse(transferExecutor.execute(request, key));

        } catch (DataIntegrityViolationException ex) {
            // Two instances handled the same retried request, both missed the lookup above, and both
            // tried to insert. The unique constraint on idempotency_key let exactly one through -
            // this is the loser, so it returns the winner's transaction rather than debiting twice.
            return findByKey(key)
                    .map(existing -> {
                        log.info("Idempotency race on key {}; returning transaction {} written by another writer",
                                key, existing.getTransactionReference());
                        return Mapper.toResponse(existing);
                    })
                    .orElseThrow(() -> ex);

        } catch (InvalidRequestException | EntityNotFoundException | AccountNotActiveException ex) {
            // The request itself was rejected before any money moved. There is no transfer to
            // record - the exception advice turns these into a 4xx.
            throw ex;

        } catch (RuntimeException ex) {
            log.error("Transfer from {} to {} failed unexpectedly", request.sourceAccountNumber(),
                    request.destinationAccountNumber(), ex);
            try {
                return Mapper.toResponse(transferExecutor.recordFailure(request, key,
                        "Transfer could not be completed due to an internal error"));
            } catch (RuntimeException recordingFailure) {
                // Bookkeeping failed on top of the original failure. Surface the cause the caller
                // actually needs; letting this one propagate would report the wrong problem.
                log.error("Could not record the failed transfer", recordingFailure);
                throw ex;
            }
        }
    }

    private Optional<Transaction> findByKey(String key) {
        return key == null ? Optional.empty() : transactionRepository.findByIdempotencyKey(key);
    }

    private String normaliseKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be at most %d characters".formatted(MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return trimmed;
    }
}

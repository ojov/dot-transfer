package com.ojo.dottransfer.utils;

import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import lombok.experimental.UtilityClass;

/** Entity to DTO conversion. Entities never leave the service layer. */
@UtilityClass
public class Mapper {

    /**
     * Reads only the denormalised columns, so rendering a page of transactions triggers no lazy
     * loads on the account relations and therefore no N+1.
     */
    public static TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getTransactionReference(),
                transaction.getTransactionDate(),
                transaction.getSourceAccountNumber(),
                transaction.getDestinationAccountNumber(),
                transaction.getAmount(),
                transaction.getTransactionFee(),
                transaction.getBilledAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getStatus(),
                transaction.getStatusMessage(),
                transaction.getCommissionWorthy(),
                transaction.getCommission(),
                transaction.getCommissionComputedAt(),
                transaction.getCreatedAt());
    }
}

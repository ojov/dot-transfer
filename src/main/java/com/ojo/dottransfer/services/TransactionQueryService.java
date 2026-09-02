package com.ojo.dottransfer.services;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.repositories.specification.TransactionSpecifications;
import com.ojo.dottransfer.utils.Mapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Read-only access to the transaction history. */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(TransactionStatus status, String accountNumber,
                                          LocalDate from, LocalDate to, Pageable pageable) {

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("`from` must not be after `to`");
        }

        Specification<Transaction> specification =
                TransactionSpecifications.withFilters(status, accountNumber, from, to);

        // Newest first is imposed here rather than trusted from the caller, so the ordering is
        // stable and always index-friendly.
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        return transactionRepository.findAll(specification, sorted).map(Mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getByReference(String reference) {
        return transactionRepository.findByTransactionReference(reference)
                .map(Mapper::toResponse)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Transaction %s not found".formatted(reference)));
    }
}

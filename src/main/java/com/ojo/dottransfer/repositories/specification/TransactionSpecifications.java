package com.ojo.dottransfer.repositories.specification;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.entities.Transaction;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional filters for the transaction listing.
 *
 * <p>Every predicate targets a column on {@code transactions} itself - the denormalised account
 * numbers and business date - so the query never joins. That is not only faster: an earlier version
 * matched the account number by walking the {@code sourceAccount}/{@code destinationAccount}
 * relations inside a single {@code OR}, which makes JPA emit two <em>inner</em> joins and silently
 * changes which rows come back.
 *
 * <p>A Specification is used rather than a JPQL query with {@code (:param is null or ...)} guards
 * because Postgres cannot infer a bind parameter's type from a bare {@code ? IS NULL}; an absent
 * filter fails at runtime. A Specification just omits the predicate.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {}

    public static Specification<Transaction> withFilters(
            TransactionStatus status, String accountNumber, LocalDate from, LocalDate to) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (accountNumber != null && !accountNumber.isBlank()) {
                // An account's history is everything it sent and everything it received.
                predicates.add(cb.or(
                        cb.equal(root.get("sourceAccountNumber"), accountNumber),
                        cb.equal(root.get("destinationAccountNumber"), accountNumber)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), from));
            }
            if (to != null) {
                // Inclusive: `to` is a day the caller named, not an exclusive instant boundary.
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

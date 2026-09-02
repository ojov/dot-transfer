package com.ojo.dottransfer.repositories;

import com.ojo.dottransfer.models.dto.StatusAggregate;
import com.ojo.dottransfer.models.entities.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByTransactionReference(String transactionReference);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * A page of transactions from {@code date} that the commission job has not assessed yet.
     *
     * <p>Deliberately not a full {@code List}: a busy day must never be loaded into memory at once.
     * Callers should repeatedly request the <em>first</em> page rather than walking offsets -
     * assessing a row removes it from this result set, so an offset-based walk would skip rows as
     * the set shrinks beneath it.
     */
    @Query("""
            select t from Transaction t
            where t.transactionDate = :date
              and t.commissionWorthy is null
            order by t.createdAt asc
            """)
    List<Transaction> findUnassessed(@Param("date") LocalDate date, Pageable pageable);

    long countByTransactionDateAndCommissionWorthyIsNull(LocalDate transactionDate);

    /**
     * The closed business days that still hold unassessed transactions, most recent first.
     *
     * <p>This is what lets the nightly job repair its own gaps: if the service was down for a
     * weekend, those days are still listed here and get picked up on the next run instead of
     * keeping {@code commissionWorthy = null} forever. Served by the
     * {@code (transaction_date, commission_worthy)} index.
     */
    @Query("""
            select distinct t.transactionDate from Transaction t
            where t.commissionWorthy is null
              and t.transactionDate < :today
            order by t.transactionDate desc
            """)
    List<LocalDate> findDatesWithUnassessedTransactions(@Param("today") LocalDate today, Pageable pageable);

    /**
     * A day's totals, bucketed by status, computed entirely in the database.
     *
     * <p>Fees and commission are summed only over the buckets the caller treats as collected -
     * a rejected transfer carries an assessed fee it was never charged, so summing blindly across
     * every status would overstate revenue.
     */
    @Query("""
            select new com.ojo.dottransfer.models.dto.StatusAggregate(
                t.status,
                count(t),
                coalesce(sum(t.amount), 0),
                coalesce(sum(t.transactionFee), 0),
                coalesce(sum(coalesce(t.commission, 0)), 0),
                coalesce(sum(case when t.commissionWorthy = true then 1L else 0L end), 0L))
            from Transaction t
            where t.transactionDate = :date
            group by t.status
            """)
    List<StatusAggregate> summariseByStatus(@Param("date") LocalDate date);
}

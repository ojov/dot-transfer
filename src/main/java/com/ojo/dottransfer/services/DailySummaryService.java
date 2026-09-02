package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.dto.StatusAggregate;
import com.ojo.dottransfer.models.entities.DailyTransactionSummary;
import com.ojo.dottransfer.models.responses.DailySummaryResponse;
import com.ojo.dottransfer.repositories.DailyTransactionSummaryRepository;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.utils.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Produces the summary of a day's transactions.
 *
 * <p>Reading and writing are strictly separated. {@link #getSummary} never writes: it serves the
 * snapshot the nightly job persisted, and computes the totals live when there is no snapshot -
 * for the current day, which is not final, or for a past day the job has not covered. Writing a
 * snapshot is {@link #generateSnapshot}'s job alone.
 *
 * <p>That split is not stylistic. When a read also persisted, two concurrent reads of the same
 * un-snapshotted day both computed and both inserted, and the unique constraint on
 * {@code summary_date} failed one of them - a plain GET returning a conflict.
 *
 * <p>Each method here is self-contained; none calls another. Spring's transaction proxy does not
 * intercept calls a bean makes to itself, so a chain would quietly run under the wrong transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final TransactionRepository transactionRepository;
    private final DailyTransactionSummaryRepository summaryRepository;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public DailySummaryResponse getSummary(LocalDate date) {
        LocalDate target = date != null ? date : businessClock.today();
        if (target.isAfter(businessClock.today())) {
            throw new InvalidRequestException("Cannot summarise a future date");
        }

        boolean provisional = businessClock.isOpen(target);

        // An open day has no meaningful snapshot - its totals are still moving - so it is always
        // computed live and flagged.
        if (!provisional) {
            Optional<DailyTransactionSummary> stored = summaryRepository.findBySummaryDate(target);
            if (stored.isPresent()) {
                return toResponse(stored.get(), false, true);
            }
        }
        return toResponse(compute(target), provisional, false);
    }

    /**
     * Computes and stores the snapshot for a day, replacing any existing one. Run by the nightly job
     * and by the manual trigger; the only path that writes.
     */
    @Transactional
    public DailySummaryResponse generateSnapshot(LocalDate date) {
        LocalDate target = date != null ? date : businessClock.previousDay();
        DailyTransactionSummary saved = persist(target, compute(target));
        log.info("Daily summary for {}: {} transactions, {} successful, fees {}, commission {}",
                target, saved.getTotalCount(), saved.getSuccessfulCount(),
                saved.getTotalFees(), saved.getTotalCommission());
        return toResponse(saved, false, false);
    }

    /**
     * Rolls the per-status buckets the database returned into one day's totals.
     *
     * <p>Fees and commission come from the SUCCESSFUL bucket alone: a rejected transfer carries an
     * assessed fee that was never charged, so summing across every status would invent revenue.
     */
    private DailyTransactionSummary compute(LocalDate date) {
        List<StatusAggregate> buckets = transactionRepository.summariseByStatus(date);
        Map<TransactionStatus, StatusAggregate> byStatus = buckets.stream()
                .collect(Collectors.toMap(StatusAggregate::status, Function.identity()));

        StatusAggregate successful = byStatus.get(TransactionStatus.SUCCESSFUL);

        return DailyTransactionSummary.builder()
                .summaryDate(date)
                .totalCount(sumLong(buckets, StatusAggregate::count))
                .successfulCount(countOf(successful))
                .failedCount(countOf(byStatus.get(TransactionStatus.FAILED)))
                .insufficientFundCount(countOf(byStatus.get(TransactionStatus.INSUFFICIENT_FUND)))
                .commissionWorthyCount(sumLong(buckets, StatusAggregate::commissionWorthyCount))
                .totalAmount(sumMoney(buckets))
                .successfulAmount(moneyOf(successful, StatusAggregate::totalAmount))
                .totalFees(moneyOf(successful, StatusAggregate::totalFees))
                .totalCommission(moneyOf(successful, StatusAggregate::totalCommission))
                .generatedAt(businessClock.now())
                .build();
    }

    /** Upserts by date, so re-running a day replaces its snapshot rather than duplicating it. */
    private DailyTransactionSummary persist(LocalDate date, DailyTransactionSummary computed) {
        return summaryRepository.findBySummaryDate(date)
                .map(existing -> {
                    existing.setTotalCount(computed.getTotalCount());
                    existing.setSuccessfulCount(computed.getSuccessfulCount());
                    existing.setFailedCount(computed.getFailedCount());
                    existing.setInsufficientFundCount(computed.getInsufficientFundCount());
                    existing.setCommissionWorthyCount(computed.getCommissionWorthyCount());
                    existing.setTotalAmount(computed.getTotalAmount());
                    existing.setSuccessfulAmount(computed.getSuccessfulAmount());
                    existing.setTotalFees(computed.getTotalFees());
                    existing.setTotalCommission(computed.getTotalCommission());
                    existing.setGeneratedAt(computed.getGeneratedAt());
                    return existing;
                })
                .orElseGet(() -> summaryRepository.save(computed));
    }

    private static DailySummaryResponse toResponse(DailyTransactionSummary summary,
                                                   boolean provisional, boolean fromSnapshot) {
        return new DailySummaryResponse(
                summary.getSummaryDate(),
                provisional,
                fromSnapshot,
                summary.getGeneratedAt(),
                summary.getTotalCount(),
                summary.getSuccessfulCount(),
                summary.getFailedCount(),
                summary.getInsufficientFundCount(),
                summary.getCommissionWorthyCount(),
                summary.getTotalAmount(),
                summary.getSuccessfulAmount(),
                summary.getTotalFees(),
                summary.getTotalCommission());
    }

    private static long countOf(StatusAggregate bucket) {
        return bucket == null ? 0L : bucket.count();
    }

    private static long sumLong(List<StatusAggregate> buckets, ToLongFunction<StatusAggregate> field) {
        return buckets.stream().mapToLong(field).sum();
    }

    private static BigDecimal sumMoney(List<StatusAggregate> buckets) {
        return MoneyUtil.normalize(buckets.stream()
                .map(StatusAggregate::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal moneyOf(StatusAggregate bucket, Function<StatusAggregate, BigDecimal> field) {
        return bucket == null ? MoneyUtil.ZERO : MoneyUtil.normalize(field.apply(bucket));
    }
}

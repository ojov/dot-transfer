package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.config.properties.JobProperties;
import com.ojo.dottransfer.models.responses.CommissionRunResponse;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.utils.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Assigns commission to transactions, a business day at a time.
 *
 * <p>Not transactional itself - it drives {@link CommissionBatchProcessor} page by page so each page
 * commits independently and a run interrupted halfway keeps the work it finished.
 *
 * <p>The page loop always asks for the <em>first</em> page rather than walking offsets. Assessing a
 * transaction removes it from the "unassessed" result set, so the set shrinks underneath an
 * offset-based walk and every page after the first would skip rows. It also makes a run idempotent:
 * a second run finds nothing left and does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    /** Stops an unterminated loop if a page somehow never clears. Should never be reached. */
    private static final int MAX_PAGES = 10_000;

    private final CommissionBatchProcessor batchProcessor;
    private final TransactionRepository transactionRepository;
    private final JobProperties jobProperties;
    private final BusinessClock businessClock;

    /** Running totals for one or more days. */
    private record Totals(int assessed, int commissionWorthy, BigDecimal commission) {

        static final Totals EMPTY = new Totals(0, 0, MoneyUtil.ZERO);

        Totals plus(Totals other) {
            return new Totals(assessed + other.assessed,
                    commissionWorthy + other.commissionWorthy,
                    commission.add(other.commission));
        }
    }

    /**
     * Assesses every closed day that still has unassessed transactions, most recent first. This is
     * what the nightly job runs.
     *
     * <p>Deliberately not "yesterday only": a job that only ever looks at the previous day leaves
     * any day missed during an outage unassessed forever, silently reporting zero commission for it.
     * Capped at {@code dot.jobs.max-backfill-days} so one run cannot take on unbounded work after a
     * long outage - the remainder is picked up by the next run.
     */
    public CommissionRunResponse assessBacklog() {
        int cap = jobProperties.maxBackfillDays();
        List<LocalDate> dates = transactionRepository.findDatesWithUnassessedTransactions(
                businessClock.today(), PageRequest.ofSize(cap));

        if (dates.isEmpty()) {
            log.info("No unassessed transactions outstanding");
            return new CommissionRunResponse(List.of(), 0, 0, MoneyUtil.ZERO);
        }
        if (dates.size() == cap) {
            log.warn("Commission backlog hit the {}-day cap; older days remain and will be picked up "
                    + "on the next run", cap);
        }

        Totals totals = Totals.EMPTY;
        for (LocalDate date : dates) {
            totals = totals.plus(assess(date));
        }

        log.info("Commission run over {} day(s) {}: assessed {}, commission-worthy {}, total commission {}",
                dates.size(), dates, totals.assessed(), totals.commissionWorthy(),
                MoneyUtil.normalize(totals.commission()));
        return toResponse(dates, totals);
    }

    /** Assesses one named day. Used by the manual trigger, and safe to re-run. */
    public CommissionRunResponse assessDay(LocalDate date) {
        LocalDate target = date != null ? date : businessClock.previousDay();
        Totals totals = assess(target);
        log.info("Commission run for {}: assessed {}, commission-worthy {}, total commission {}",
                target, totals.assessed(), totals.commissionWorthy(),
                MoneyUtil.normalize(totals.commission()));
        return toResponse(List.of(target), totals);
    }

    private Totals assess(LocalDate date) {
        Totals totals = Totals.EMPTY;
        for (int page = 0; page < MAX_PAGES; page++) {
            CommissionBatchProcessor.PageResult result = batchProcessor.assessNextPage(date);
            if (result.assessed() == 0) {
                return totals;
            }
            totals = totals.plus(new Totals(result.assessed(), result.commissionWorthy(), result.commission()));
        }
        log.warn("Commission assessment for {} stopped at the {}-page ceiling with work still "
                + "outstanding; investigate before relying on this day's figures", date, MAX_PAGES);
        return totals;
    }

    private static CommissionRunResponse toResponse(List<LocalDate> dates, Totals totals) {
        return new CommissionRunResponse(dates, totals.assessed(), totals.commissionWorthy(),
                MoneyUtil.normalize(totals.commission()));
    }
}

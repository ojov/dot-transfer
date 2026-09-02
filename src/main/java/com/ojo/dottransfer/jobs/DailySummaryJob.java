package com.ojo.dottransfer.jobs;

import com.ojo.dottransfer.services.DailySummaryService;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Snapshots the previous business day once it is closed, so the summary endpoint can serve a stable
 * answer without re-aggregating the table on every request.
 *
 * <p>Scheduled after {@link CommissionJob} so the commission figures it records are the final ones.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryJob {

    private final DailySummaryService dailySummaryService;

    @Scheduled(cron = "${dot.jobs.summary-cron}", zone = "${dot.business.zone}")
    @SchedulerLock(
            name = "daily-summary",
            lockAtMostFor = "${dot.jobs.lock-at-most-for}",
            lockAtLeastFor = "${dot.jobs.lock-at-least-for}")
    public void run() {
        LockAssert.assertLocked();
        dailySummaryService.generateSnapshot(null);
    }
}

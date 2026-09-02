package com.ojo.dottransfer.jobs;

import com.ojo.dottransfer.services.CommissionService;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Assigns commission to any closed business day that still has unassessed transactions - normally
 * just the previous one, but also anything missed while the service was down.
 *
 * <p>Every instance holds this trigger, so all of them wake at the same moment; ShedLock lets
 * exactly one through. It must complete before {@link DailySummaryJob} runs, or the day's snapshot
 * reports a commission total that has not been computed yet - hence the gap between the two crons.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionJob {

    private final CommissionService commissionService;

    @Scheduled(cron = "${dot.jobs.commission-cron}", zone = "${dot.business.zone}")
    @SchedulerLock(
            name = "commission-assessment",
            lockAtMostFor = "${dot.jobs.lock-at-most-for}",
            lockAtLeastFor = "${dot.jobs.lock-at-least-for}")
    public void run() {
        // Fails loudly if the lock was never applied - a misconfigured LockProvider would otherwise
        // let every instance run the job and be invisible until the numbers were already wrong.
        LockAssert.assertLocked();
        commissionService.assessBacklog();
    }
}

package com.ojo.dottransfer.services;

import com.ojo.dottransfer.config.BusinessClock;
import com.ojo.dottransfer.config.properties.FeeProperties;
import com.ojo.dottransfer.config.properties.JobProperties;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.entities.Transaction;
import com.ojo.dottransfer.repositories.TransactionRepository;
import com.ojo.dottransfer.utils.FeeCalculator;
import com.ojo.dottransfer.utils.MoneyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Assesses one bounded page of transactions per call, each in its own committed transaction.
 *
 * <p>Separate from {@link CommissionService} for two reasons: Spring's proxy only applies
 * {@code @Transactional} to calls that arrive from outside the bean, and the per-page commit is the
 * whole point - if a pod dies partway through a busy day, the pages it finished stay done and the
 * next run resumes from the remainder rather than starting over.
 */
@Service
@RequiredArgsConstructor
public class CommissionBatchProcessor {

    private final TransactionRepository transactionRepository;
    private final FeeProperties feeProperties;
    private final JobProperties jobProperties;
    private final BusinessClock businessClock;

    /** Assessment outcome for a single page. */
    public record PageResult(int assessed, int commissionWorthy, BigDecimal commission) {
        boolean isEmpty() {
            return assessed == 0;
        }
    }

    @Transactional
    public PageResult assessNextPage(LocalDate date) {
        List<Transaction> page = transactionRepository.findUnassessed(
                date, PageRequest.ofSize(jobProperties.batchSize()));

        if (page.isEmpty()) {
            return new PageResult(0, 0, MoneyUtil.ZERO);
        }

        int worthy = 0;
        BigDecimal commissionTotal = MoneyUtil.ZERO;

        for (Transaction transaction : page) {
            // Only a completed transfer earns anything - a rejected one was assessed a fee it was
            // never actually charged. Both are marked, so nothing is left in the unassessed state.
            boolean isSuccessful = transaction.getStatus() == TransactionStatus.SUCCESSFUL;
            boolean isWorthy = isSuccessful
                    && FeeCalculator.isCommissionWorthy(transaction.getTransactionFee(), feeProperties);

            BigDecimal commission = isWorthy
                    ? FeeCalculator.computeCommission(transaction.getTransactionFee(), feeProperties)
                    : MoneyUtil.ZERO;

            transaction.setCommissionWorthy(isWorthy);
            transaction.setCommission(commission);
            transaction.setCommissionComputedAt(businessClock.now());

            if (isWorthy) {
                worthy++;
                commissionTotal = commissionTotal.add(commission);
            }
        }

        // Dirty checking flushes the updates at commit; no explicit save call needed.
        return new PageResult(page.size(), worthy, MoneyUtil.normalize(commissionTotal));
    }
}

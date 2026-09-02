package com.ojo.dottransfer.utils;

import com.ojo.dottransfer.config.properties.FeeProperties;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The brief's money rules, in one place:
 * <pre>
 *   fee        = min(amount x 0.5%, 100)   - the cap binds from amount >= 20,000
 *   billed     = amount + fee              - the sender pays the fee on top
 *   commission = fee x 20%                 - successful transactions only
 * </pre>
 * Rates come from {@link FeeProperties} so none of these numbers are literals.
 */
@UtilityClass
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Extra digits kept during percentage division so the rounding happens once, at the end. */
    private static final int RATE_SCALE = 10;

    /** The fee for a transfer of {@code amount}: a percentage of it, capped. Zero for a null/zero amount. */
    public static BigDecimal computeFee(BigDecimal amount, FeeProperties properties) {
        if (!MoneyUtil.isPositive(amount)) {
            return MoneyUtil.ZERO;
        }
        BigDecimal rate = properties.percent().divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_EVEN);
        // Round to the canonical scale before clamping, not after: clamping a raw value that later
        // rounds up could otherwise return a fee a fraction above the cap.
        BigDecimal fee = MoneyUtil.normalize(amount.multiply(rate));
        BigDecimal cap = MoneyUtil.normalize(properties.cap());
        return fee.compareTo(cap) > 0 ? cap : fee;
    }

    /** What the sender is actually debited: the transfer amount plus the fee they bear. */
    public static BigDecimal computeBilledAmount(BigDecimal amount, BigDecimal fee) {
        return MoneyUtil.normalize(amount.add(fee));
    }

    /** The platform's cut of a fee. Applied by the nightly job, never at transfer time. */
    public static BigDecimal computeCommission(BigDecimal fee, FeeProperties properties) {
        if (!MoneyUtil.isPositive(fee)) {
            return MoneyUtil.ZERO;
        }
        BigDecimal rate = properties.commissionPercent().divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_EVEN);
        return MoneyUtil.normalize(fee.multiply(rate));
    }

    /**
     * Whether a successful transaction earns commission. A fee at or below the configured floor
     * earns nothing - there is no commission to take a percentage of.
     */
    public static boolean isCommissionWorthy(BigDecimal fee, FeeProperties properties) {
        return fee != null && fee.compareTo(properties.commissionWorthyMinFee()) > 0;
    }
}

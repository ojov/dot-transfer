package com.ojo.dottransfer.utils;

import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Canonical money representation: {@link BigDecimal} at scale {@value #SCALE} with
 * {@code HALF_EVEN}. Normalize wherever a money value is <em>produced</em> (fee computation,
 * commission, balance arithmetic) so persisted values are always scale-2.
 *
 * <p>Never compare money with {@code equals()} - {@code BigDecimal.equals} is scale-sensitive, so
 * {@code 100} is not equal to {@code 100.00}. Use {@code compareTo} or {@code signum}.
 */
@UtilityClass
public class MoneyUtil {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    /** Canonical scale-{@value #SCALE} money; null-safe (null maps to null). */
    public static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, ROUNDING);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** True when {@code balance} can cover {@code required}. Scale-insensitive by design. */
    public static boolean covers(BigDecimal balance, BigDecimal required) {
        return balance.compareTo(required) >= 0;
    }
}

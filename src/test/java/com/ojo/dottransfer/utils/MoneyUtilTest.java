package com.ojo.dottransfer.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilTest {

    @Test
    void normalizesToKoboPrecision() {
        assertThat(MoneyUtil.normalize(new BigDecimal("10"))).isEqualByComparingTo("10.00");
        assertThat(MoneyUtil.normalize(new BigDecimal("10.005"))).isEqualByComparingTo("10.00");
        assertThat(MoneyUtil.normalize(new BigDecimal("10.015"))).isEqualByComparingTo("10.02");
    }

    @Test
    void roundsHalfToEvenNotHalfUp() {
        // Banker's rounding: an exact half goes to the even neighbour, so a long run of roundings
        // does not drift upward the way HALF_UP does.
        assertThat(MoneyUtil.normalize(new BigDecimal("0.125"))).isEqualByComparingTo("0.12");
        assertThat(MoneyUtil.normalize(new BigDecimal("0.135"))).isEqualByComparingTo("0.14");
    }

    @Test
    void normalizeIsNullSafe() {
        assertThat(MoneyUtil.normalize(null)).isNull();
    }

    @Test
    void coversIgnoresScale() {
        // The reason `covers` exists at all: BigDecimal.equals would call these two different,
        // and a balance read back from the database rarely has the scale it was written with.
        assertThat(MoneyUtil.covers(new BigDecimal("100.00"), new BigDecimal("100"))).isTrue();
        assertThat(MoneyUtil.covers(new BigDecimal("100.0000"), new BigDecimal("100.00"))).isTrue();
    }

    @Test
    void coversRejectsAShortfallOfOneKobo() {
        assertThat(MoneyUtil.covers(new BigDecimal("99.99"), new BigDecimal("100.00"))).isFalse();
    }

    @Test
    void isPositiveExcludesZeroAndNull() {
        assertThat(MoneyUtil.isPositive(new BigDecimal("0.01"))).isTrue();
        assertThat(MoneyUtil.isPositive(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtil.isPositive(new BigDecimal("-1"))).isFalse();
        assertThat(MoneyUtil.isPositive(null)).isFalse();
    }
}

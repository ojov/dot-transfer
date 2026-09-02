package com.ojo.dottransfer.utils;

import com.ojo.dottransfer.config.properties.FeeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brief's money rules, which is where a mistake costs real money and where the rules are pure
 * functions - no Spring, no database, no mocks. Every assertion uses
 * {@code isEqualByComparingTo} rather than {@code isEqualTo}, because {@code BigDecimal.equals} is
 * scale-sensitive and would call 100 unequal to 100.00.
 */
class FeeCalculatorTest {

    // The production defaults: 0.5% capped at 100, commission 20% of the fee, worthy above 0.
    private final FeeProperties properties = new FeeProperties(
            new BigDecimal("0.5"), new BigDecimal("100"),
            new BigDecimal("20"), BigDecimal.ZERO);

    @Nested
    @DisplayName("fee = 0.5% of amount, capped at 100")
    class Fee {

        @Test
        void ordinaryAmountTakesThePercentage() {
            assertThat(FeeCalculator.computeFee(new BigDecimal("10000.00"), properties))
                    .isEqualByComparingTo("50.00");
        }

        @Test
        void capBindsExactlyAtTwentyThousand() {
            // 0.5% of 20,000 is exactly 100 - the point where the percentage and the cap meet.
            // Immediately below it the percentage governs; above it the cap does.
            assertThat(FeeCalculator.computeFee(new BigDecimal("20000.00"), properties))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        void justBelowTheCapIsStillGovernedByThePercentage() {
            assertThat(FeeCalculator.computeFee(new BigDecimal("19000.00"), properties))
                    .isEqualByComparingTo("95.00");
        }

        @Test
        void largeAmountIsCappedNotScaled() {
            // 0.5% of 50,000 would be 250. The cap is the whole point of this test.
            assertThat(FeeCalculator.computeFee(new BigDecimal("50000.00"), properties))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        void veryLargeAmountIsStillCapped() {
            assertThat(FeeCalculator.computeFee(new BigDecimal("100000000.00"), properties))
                    .isEqualByComparingTo("100.00");
        }

        @Test
        void fractionalFeeRoundsToKobo() {
            // 0.5% of 333.33 = 1.66665, which has to land on a real kobo value.
            assertThat(FeeCalculator.computeFee(new BigDecimal("333.33"), properties))
                    .isEqualByComparingTo("1.67");
        }

        @Test
        void amountTooSmallToAttractAFee() {
            // 0.5% of 0.01 is 0.00005 - below half a kobo, so it rounds away entirely.
            assertThat(FeeCalculator.computeFee(new BigDecimal("0.01"), properties))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void zeroAndNullAreFreeRatherThanExplosive() {
            assertThat(FeeCalculator.computeFee(BigDecimal.ZERO, properties)).isEqualByComparingTo("0.00");
            assertThat(FeeCalculator.computeFee(null, properties)).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the sender bears the fee")
    class BilledAmount {

        @Test
        void billedIsAmountPlusFee() {
            BigDecimal amount = new BigDecimal("50000.00");
            BigDecimal fee = FeeCalculator.computeFee(amount, properties);

            // This is the rule that makes an account holding exactly the transfer amount unable to
            // afford the transfer.
            assertThat(FeeCalculator.computeBilledAmount(amount, fee))
                    .isEqualByComparingTo("50100.00");
        }
    }

    @Nested
    @DisplayName("commission = 20% of the fee")
    class Commission {

        @Test
        void commissionOnACappedFee() {
            assertThat(FeeCalculator.computeCommission(new BigDecimal("100.00"), properties))
                    .isEqualByComparingTo("20.00");
        }

        @Test
        void commissionRoundsToKobo() {
            // 20% of 1.67 = 0.334
            assertThat(FeeCalculator.computeCommission(new BigDecimal("1.67"), properties))
                    .isEqualByComparingTo("0.33");
        }

        @Test
        void noFeeMeansNoCommission() {
            assertThat(FeeCalculator.computeCommission(BigDecimal.ZERO, properties))
                    .isEqualByComparingTo("0.00");
            assertThat(FeeCalculator.computeCommission(null, properties))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void worthinessTracksTheConfiguredFloor() {
            assertThat(FeeCalculator.isCommissionWorthy(new BigDecimal("0.01"), properties)).isTrue();
            assertThat(FeeCalculator.isCommissionWorthy(new BigDecimal("0.00"), properties)).isFalse();
            assertThat(FeeCalculator.isCommissionWorthy(null, properties)).isFalse();
        }

        @Test
        void aFeeThatRoundedAwayEarnsNothing() {
            // Ties the two rules together: a transfer too small to attract a fee cannot be
            // commission-worthy, because there is no fee to take a percentage of.
            BigDecimal fee = FeeCalculator.computeFee(new BigDecimal("0.01"), properties);
            assertThat(FeeCalculator.isCommissionWorthy(fee, properties)).isFalse();
        }
    }

    @Nested
    @DisplayName("rates come from configuration, not from constants in the code")
    class Configurable {

        @Test
        void aDifferentRateAndCapChangeTheOutcome() {
            FeeProperties custom = new FeeProperties(
                    new BigDecimal("2"), new BigDecimal("500"),
                    new BigDecimal("50"), new BigDecimal("10"));

            assertThat(FeeCalculator.computeFee(new BigDecimal("1000.00"), custom))
                    .isEqualByComparingTo("20.00");
            assertThat(FeeCalculator.computeCommission(new BigDecimal("20.00"), custom))
                    .isEqualByComparingTo("10.00");
            // This configuration only pays commission above a fee of 10, so 20 qualifies and 5 does not.
            assertThat(FeeCalculator.isCommissionWorthy(new BigDecimal("20.00"), custom)).isTrue();
            assertThat(FeeCalculator.isCommissionWorthy(new BigDecimal("5.00"), custom)).isFalse();
        }
    }
}

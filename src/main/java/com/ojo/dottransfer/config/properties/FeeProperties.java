package com.ojo.dottransfer.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Fee and commission rates, externalised so none of the brief's numbers are literals in code and
 * they can be changed per environment without a rebuild.
 *
 * <p>Every component carries a default. Without one, an absent property binds to {@code null} and
 * the first transfer of the day fails with a {@code NullPointerException} deep in the fee maths -
 * a misconfiguration that would surface as a runtime error rather than at startup.
 *
 * @param percent                fee rate applied to the transfer amount, as a percentage (0.5 = 0.5%)
 * @param cap                    maximum fee, whatever the amount (binds from amount >= 20,000 at 0.5%)
 * @param commissionPercent      commission rate applied to the fee, as a percentage (20 = 20%)
 * @param commissionWorthyMinFee a successful transfer earns commission only when its fee exceeds this
 */
@ConfigurationProperties(prefix = "dot.fee")
public record FeeProperties(
        @DefaultValue("0.5") BigDecimal percent,
        @DefaultValue("100") BigDecimal cap,
        @DefaultValue("20") BigDecimal commissionPercent,
        @DefaultValue("0") BigDecimal commissionWorthyMinFee) {}

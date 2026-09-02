package com.ojo.dottransfer.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param batchSize       how many transactions the commission job claims per page. Bounded on
 *                        purpose - a busy day must never be loaded into memory at once. Defaulted
 *                        because an absent value would bind to {@code 0}, and a zero-sized page
 *                        request throws.
 * @param maxBackfillDays the most days one commission run will catch up on. Caps the work a single
 *                        run can take on after a long outage; anything left over is picked up by
 *                        the next run.
 */
@ConfigurationProperties(prefix = "dot.jobs")
public record JobProperties(
        @DefaultValue("500") int batchSize,
        @DefaultValue("30") int maxBackfillDays) {}

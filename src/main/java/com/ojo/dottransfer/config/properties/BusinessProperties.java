package com.ojo.dottransfer.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param zone the timezone that defines a business day, e.g. {@code Africa/Lagos}. Every instance
 *             must read the same value: if two pods disagree on where a day starts, a transaction
 *             lands on different dates depending on which pod served it and the daily summary
 *             silently splits.
 */
@ConfigurationProperties(prefix = "dot.business")
public record BusinessProperties(@DefaultValue("Africa/Lagos") String zone) {}

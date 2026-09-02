package com.ojo.dottransfer.config;

import com.ojo.dottransfer.config.properties.BusinessProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The single place that answers "which business day is this?".
 *
 * <p>Instants are stored in UTC, but a transaction belongs to a day in the business timezone, and
 * that boundary has to be identical on every instance. Centralising it here means the transfer path,
 * the commission job and the daily summary cannot drift apart on the definition.
 */
@Component
public class BusinessClock {

    private final ZoneId zone;
    private final Clock clock;

    public BusinessClock(BusinessProperties properties, Clock clock) {
        this.zone = ZoneId.of(properties.zone());
        this.clock = clock;
    }

    public ZoneId zone() {
        return zone;
    }

    public Instant now() {
        return clock.instant();
    }

    /** The business day an instant falls on. */
    public LocalDate dateOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    public LocalDate today() {
        return dateOf(clock.instant());
    }

    /** The most recently completed business day - what the nightly jobs process. */
    public LocalDate previousDay() {
        return today().minusDays(1);
    }

    /** True when the given day is still open, so any summary of it is provisional. */
    public boolean isOpen(LocalDate date) {
        return !date.isBefore(today());
    }
}

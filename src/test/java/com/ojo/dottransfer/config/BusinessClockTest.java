package com.ojo.dottransfer.config;

import com.ojo.dottransfer.config.properties.BusinessProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinning "now" to a fixed {@link Clock} is the entire reason {@code BusinessClock} takes one as a
 * collaborator rather than calling {@code Instant.now()}. Without that, these boundary cases could
 * only be tested by waiting for midnight.
 */
class BusinessClockTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos"); // UTC+1, no DST

    private static BusinessClock clockAt(String instant) {
        return new BusinessClock(new BusinessProperties("Africa/Lagos"),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void businessDayIsResolvedInTheConfiguredZoneNotUtc() {
        // 23:30 UTC is already 00:30 the following day in Lagos. A transaction at this moment
        // belongs to the 3rd, and anything deriving the day from the UTC instant would file it
        // under the 2nd. This is precisely the disagreement the stored transaction_date prevents,
        // and why every instance must read the same zone.
        BusinessClock clock = clockAt("2026-09-02T23:30:00Z");

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(clock.dateOf(Instant.parse("2026-09-02T23:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void justBeforeTheLocalMidnightStillBelongsToTheEarlierDay() {
        // 22:59 UTC is 23:59 in Lagos - the last minute of the 2nd.
        assertThat(clockAt("2026-09-02T22:59:00Z").today()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void previousDayIsTheMostRecentlyClosedBusinessDay() {
        assertThat(clockAt("2026-09-02T09:00:00Z").previousDay()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void previousDayCrossesAMonthBoundary() {
        assertThat(clockAt("2026-09-01T09:00:00Z").previousDay()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void todayIsOpenAndYesterdayIsClosed() {
        BusinessClock clock = clockAt("2026-09-02T09:00:00Z");

        // "Open" decides whether a summary is provisional, so it must be true for today and for
        // anything later, and false the moment a day is behind us.
        assertThat(clock.isOpen(LocalDate.of(2026, 9, 2))).isTrue();
        assertThat(clock.isOpen(LocalDate.of(2026, 9, 3))).isTrue();
        assertThat(clock.isOpen(LocalDate.of(2026, 9, 1))).isFalse();
    }

    @Test
    void exposesTheConfiguredZone() {
        assertThat(clockAt("2026-09-02T09:00:00Z").zone()).isEqualTo(LAGOS);
    }
}

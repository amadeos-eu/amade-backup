package eu.amadeos.apps.backup.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleTest {
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    // A Wednesday
    static final ZonedDateTime NOON = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, BERLIN);

    @Test
    void aDailyJobsLastTimeIsYesterdayBeforeItsHour() {
        Schedule daily = new Schedule(Schedule.Frequency.DAILY, LocalTime.of(21, 0), DayOfWeek.SUNDAY, false);
        assertEquals(Optional.of(NOON.minusDays(1).withHour(21)), daily.lastSlot(NOON));
        assertEquals(Optional.of(NOON.withHour(21)), daily.lastSlot(NOON.withHour(21)));
        assertEquals(Optional.of(NOON.withHour(21)), daily.lastSlot(NOON.withHour(23)));
    }

    @Test
    void aWeeklyJobsLastTimeIsThePreviousDayOfItsWeek() {
        Schedule sunday = new Schedule(Schedule.Frequency.WEEKLY, LocalTime.of(10, 0), DayOfWeek.SUNDAY, false);
        assertEquals(Optional.of(ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, BERLIN)), sunday.lastSlot(NOON));
        Schedule wednesday = new Schedule(Schedule.Frequency.WEEKLY, LocalTime.of(13, 0), DayOfWeek.WEDNESDAY, false);
        assertEquals(Optional.of(NOON.minusWeeks(1).withHour(13)), wednesday.lastSlot(NOON));
        assertEquals(Optional.of(NOON.withHour(13)), wednesday.lastSlot(NOON.withHour(14)));
    }

    @Test
    void aJobStartedByHandHasNoTimeAndIsNeverOverdue() {
        assertEquals(Optional.empty(), Schedule.MANUAL.lastSlot(NOON));
        assertFalse(Schedule.MANUAL.overdue(Optional.empty(), NOON.toInstant()));
    }

    @Test
    void dailyIsOverdueAfterTwoDaysWeeklyAfterNine() {
        Instant now = NOON.toInstant();
        Schedule weekly = new Schedule(Schedule.Frequency.WEEKLY, LocalTime.of(10, 0), DayOfWeek.SUNDAY, false);
        assertFalse(Schedule.DEFAULT.overdue(Optional.of(NOON.minusDays(1).toInstant()), now));
        assertTrue(Schedule.DEFAULT.overdue(Optional.of(NOON.minusDays(2).toInstant()), now));
        assertFalse(weekly.overdue(Optional.of(NOON.minusDays(8).toInstant()), now));
        assertTrue(weekly.overdue(Optional.of(NOON.minusDays(9).toInstant()), now));
        assertTrue(weekly.overdue(Optional.empty(), now));
    }
}

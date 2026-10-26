package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.Target;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlannerTest {
    static final ZonedDateTime NINE_PM = ZonedDateTime.of(2026, 10, 28, 21, 0, 0, 0, ZoneOffset.UTC);
    static final Instant LONG_AGO = NINE_PM.minusYears(1).toInstant();

    static Job job(Schedule schedule) {
        return Job.backup("docs", "Documents", List.of(Path.of("/home/ralf/Documents")),
                Target.onDisk("1234-ABCD", "BACKUP", Path.of("amadeos-backup/docs"))).withSchedule(schedule);
    }

    static RunRecord run(ZonedDateTime when, RunResult result) {
        return new RunRecord(when.toInstant(), Duration.ofMinutes(3), result, "manual", 10, 1, 0, 0, 100, "abc", "");
    }

    static Optional<String> trigger(Job job, List<RunRecord> runs, ZonedDateTime now, boolean present, boolean pluggedIn) {
        return Planner.trigger(job, runs, LONG_AGO, now, present, pluggedIn);
    }

    @Test
    void aDailyJobRunsAtItsTimeAndOnlyOnce() {
        Job daily = job(Schedule.DEFAULT);
        List<RunRecord> yesterday = List.of(run(NINE_PM.minusDays(1), RunResult.OK));
        assertEquals(Optional.empty(), trigger(daily, yesterday, NINE_PM.minusMinutes(1), true, false));
        assertEquals(Optional.of("schedule"), trigger(daily, yesterday, NINE_PM.plusMinutes(1), true, false));
        List<RunRecord> today = List.of(run(NINE_PM.plusMinutes(1), RunResult.OK), yesterday.getFirst());
        assertEquals(Optional.empty(), trigger(daily, today, NINE_PM.plusMinutes(40), true, false));
    }

    @Test
    void aMissedTimeIsMadeUpWhenTheDiskIsThere() {
        Job daily = job(Schedule.DEFAULT);
        List<RunRecord> old = List.of(run(NINE_PM.minusDays(3), RunResult.OK));
        ZonedDateTime morning = NINE_PM.plusHours(12);
        assertEquals(Optional.empty(), trigger(daily, old, morning, false, false));
        assertEquals(Optional.of("catch-up"), trigger(daily, old, morning, true, false));
    }

    @Test
    void aFailedRunIsNotRepeatedBeforeTheNextTimeButAMissingDiskIs() {
        Job daily = job(Schedule.DEFAULT);
        ZonedDateTime later = NINE_PM.plusHours(1);
        assertEquals(Optional.empty(), trigger(daily, List.of(run(NINE_PM, RunResult.FAILED)), later, true, false));
        assertEquals(Optional.of("catch-up"),
                trigger(daily, List.of(run(NINE_PM, RunResult.TARGET_MISSING)), later, true, false));
    }

    @Test
    void aNewJobDoesNotMakeUpForTimesBeforeTheHelperKnewIt() {
        Job daily = job(Schedule.DEFAULT);
        ZonedDateTime afternoon = NINE_PM.minusHours(6);
        assertEquals(Optional.empty(), Planner.trigger(daily, List.of(), afternoon.toInstant(), afternoon, true, false));
        assertEquals(Optional.of("schedule"),
                Planner.trigger(daily, List.of(), afternoon.toInstant(), NINE_PM.plusMinutes(2), true, false));
    }

    @Test
    void aConnectedDiskStartsTheJobUnlessItJustRan() {
        Job onlyPlugged = job(new Schedule(Schedule.Frequency.MANUAL, LocalTime.NOON, DayOfWeek.MONDAY, true));
        List<RunRecord> recent = List.of(run(NINE_PM.minusMinutes(30), RunResult.OK));
        List<RunRecord> older = List.of(run(NINE_PM.minusHours(2), RunResult.OK));
        assertEquals(Optional.empty(), trigger(onlyPlugged, older, NINE_PM, true, false));
        assertEquals(Optional.of("plugged-in"), trigger(onlyPlugged, older, NINE_PM, true, true));
        assertEquals(Optional.empty(), trigger(onlyPlugged, recent, NINE_PM, true, true));
        assertEquals(Optional.empty(), trigger(job(Schedule.MANUAL), older, NINE_PM, true, true));
    }
}

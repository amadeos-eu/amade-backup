package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.apps.backup.bootstrap.BackupModule;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.commons.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {
    static final Messages EN = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.UK);
    static final Messages DE = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.GERMANY);

    @Test
    void sizesCountInThousandsLikeTheFileManager() {
        Formats en = new Formats(EN, ZoneOffset.UTC);
        assertEquals("999 B", en.bytes(999));
        assertEquals("214 KB", en.bytes(214_000));
        assertEquals("3.1 GB", en.bytes(3_140_000_000L));
        assertEquals("389 GB", en.bytes(389_000_000_000L));
        assertEquals("3,1 GB", new Formats(DE, ZoneOffset.UTC).bytes(3_140_000_000L));
        assertEquals("12.000", new Formats(DE, ZoneOffset.UTC).count(12000));
    }

    @Test
    void timesAreRelativeToToday() {
        Formats en = new Formats(EN, ZoneOffset.UTC);
        Instant now = Instant.parse("2026-10-25T22:00:00Z");
        assertEquals("Today 21:00", en.when(Instant.parse("2026-10-25T21:00:00Z"), now));
        assertEquals("Yesterday 09:05", en.when(Instant.parse("2026-10-24T09:05:00Z"), now));
        assertEquals("Fri 2 Oct 21:00", en.when(Instant.parse("2026-10-02T21:00:00Z"), now));
        assertEquals("31 Dec 2025 21:00", en.when(Instant.parse("2025-12-31T21:00:00Z"), now));
        assertEquals("Heute 21:00", new Formats(DE, ZoneOffset.UTC).when(Instant.parse("2026-10-25T21:00:00Z"), now));
    }

    @Test
    void durationsAreShort() {
        Formats en = new Formats(EN, ZoneOffset.UTC);
        assertEquals("35 s", en.duration(Duration.ofSeconds(35)));
        assertEquals("2 min", en.duration(Duration.ofSeconds(100)));
        assertEquals("1 h 05 min", en.duration(Duration.ofMinutes(65)));
    }

    @Test
    void schedulesAndKeepPoliciesReadAsSentences() {
        assertEquals("Every day at 21:00, and when the disk is connected", Describe.schedule(EN, Schedule.DEFAULT));
        assertEquals("Jeden Freitag um 07:30", Describe.schedule(DE,
                new Schedule(Schedule.Frequency.WEEKLY, LocalTime.of(7, 30), DayOfWeek.FRIDAY, false)));
        assertEquals("All of the last 2 days, then 7 daily, 8 weekly, 12 monthly, 100 yearly", Describe.keep(EN, KeepPolicy.DEFAULT));
    }
}

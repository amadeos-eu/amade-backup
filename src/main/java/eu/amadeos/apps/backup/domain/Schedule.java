package eu.amadeos.apps.backup.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * When a job runs by itself: never, every day or once a week at a time, and in addition whenever its disk is
 * plugged in. A run that was missed because the computer was off or the disk absent is made up later.
 */
public record Schedule(Frequency frequency, LocalTime time, DayOfWeek day, boolean whenPluggedIn) {
    public enum Frequency {
        MANUAL, DAILY, WEEKLY;

        public String key() {
            return name().toLowerCase();
        }
    }

    /** Every day at 21:00 and when the disk is plugged in. */
    public static final Schedule DEFAULT = new Schedule(Frequency.DAILY, LocalTime.of(21, 0), DayOfWeek.SUNDAY, true);

    /** Only when started by hand. */
    public static final Schedule MANUAL = new Schedule(Frequency.MANUAL, LocalTime.of(21, 0), DayOfWeek.SUNDAY, false);

    public Schedule {
        if (frequency == null || time == null || day == null) throw new IllegalArgumentException("Incomplete schedule");
    }

    public boolean automatic() {
        return frequency != Frequency.MANUAL || whenPluggedIn;
    }

    // --8<-- [start:slot]
    /** The newest time at or before {@code now} at which the job was to run; empty for a job started by hand. */
    public Optional<ZonedDateTime> lastSlot(ZonedDateTime now) {
        ZonedDateTime today = now.with(time);
        return switch (frequency) {
            case MANUAL -> Optional.empty();
            case DAILY -> Optional.of(today.isAfter(now) ? today.minusDays(1) : today);
            case WEEKLY -> {
                ZonedDateTime slot = today.with(TemporalAdjusters.previousOrSame(day));
                yield Optional.of(slot.isAfter(now) ? slot.minusWeeks(1) : slot);
            }
        };
    }
    // --8<-- [end:slot]

    /** No saved run for longer than the schedule allows: 2 days for a daily job, 9 for a weekly one. */
    public boolean overdue(Optional<Instant> lastSaved, Instant now) {
        if (frequency == Frequency.MANUAL) return false;
        if (lastSaved.isEmpty()) return true;
        long days = Duration.between(lastSaved.get(), now).toDays();
        return frequency == Frequency.DAILY ? days >= 2 : days >= 9;
    }
}

package eu.amadeos.apps.backup.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;

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
}

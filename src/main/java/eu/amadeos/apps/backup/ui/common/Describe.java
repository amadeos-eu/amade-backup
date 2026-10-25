package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.commons.i18n.Messages;

import java.time.format.TextStyle;

/** Schedules and keep policies in words. */
public final class Describe {
    private Describe() {
    }

    /** "Every day at 21:00, and whenever the disk is plugged in". */
    public static String schedule(Messages msg, Schedule s) {
        String time = s.time().toString();
        String base = switch (s.frequency()) {
            case MANUAL -> msg.get("schedule.manual");
            case DAILY -> msg.format("schedule.daily", time);
            case WEEKLY -> msg.format("schedule.weekly", s.day().getDisplayName(TextStyle.FULL, msg.locale()), time);
        };
        if (s.whenPluggedIn()) base += msg.get("schedule.plugged-in");
        return base;
    }

    /** "All of the last 2 days, then 7 daily, 8 weekly, 12 monthly, 100 yearly". */
    public static String keep(Messages msg, KeepPolicy k) {
        return msg.format("keep.text", k.withinDays(), k.daily(), k.weekly(), k.monthly(), k.yearly());
    }
}

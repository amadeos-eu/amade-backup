package eu.amadeos.apps.backup.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Which backups stay when old ones are thinned out: every backup of the last {@code withinDays} days, then
 * the newest one of each of the last {@code daily} days, {@code weekly} weeks, {@code monthly} months and
 * {@code yearly} years. A count of 0 keeps none of that kind.
 */
public record KeepPolicy(int withinDays, int daily, int weekly, int monthly, int yearly) {
    /** Two days of everything, 7 daily, 8 weekly, 12 monthly, then one a year for 100 years. */
    public static final KeepPolicy DEFAULT = new KeepPolicy(2, 7, 8, 12, 100);

    public KeepPolicy {
        if (withinDays < 0 || daily < 0 || weekly < 0 || monthly < 0 || yearly < 0) {
            throw new IllegalArgumentException("Negative keep count");
        }
    }

    /** True if nothing would be kept apart from the newest backup. */
    public boolean keepsOnlyLatest() {
        return withinDays == 0 && daily == 0 && weekly == 0 && monthly == 0 && yearly == 0;
    }

    /** The options of {@code restic forget}; the newest backup is always kept. */
    // --8<-- [start:restic]
    public List<String> resticOptions() {
        List<String> options = new ArrayList<>(List.of("--keep-last", "1"));
        if (withinDays > 0) options.addAll(List.of("--keep-within", withinDays + "d"));
        if (daily > 0) options.addAll(List.of("--keep-daily", Integer.toString(daily)));
        if (weekly > 0) options.addAll(List.of("--keep-weekly", Integer.toString(weekly)));
        if (monthly > 0) options.addAll(List.of("--keep-monthly", Integer.toString(monthly)));
        if (yearly > 0) options.addAll(List.of("--keep-yearly", Integer.toString(yearly)));
        return options;
    }
    // --8<-- [end:restic]
}

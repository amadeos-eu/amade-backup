package eu.amadeos.apps.backup.domain;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The backups of a repository, newest first, under headings: today, yesterday, this week, each earlier month
 * of this year and each earlier year.
 */
public final class Timeline {
    public sealed interface Row permits Heading, Entry {
    }

    /**
     * @param kind  "today", "yesterday", "week", "month" or "year"
     * @param month the month for "month"
     * @param year  the year for "year"
     */
    public record Heading(String kind, YearMonth month, Year year) implements Row {
    }

    public record Entry(Snapshot snapshot) implements Row {
    }

    private Timeline() {
    }

    // --8<-- [start:of]
    public static List<Row> of(List<Snapshot> snapshots, LocalDate today, ZoneId zone, Locale locale) {
        List<Snapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparing(Snapshot::time).reversed());
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).getFirstDayOfWeek()));
        List<Row> rows = new ArrayList<>();
        Heading last = null;
        for (Snapshot s : sorted) {
            LocalDate day = s.time().atZone(zone).toLocalDate();
            Heading heading;
            if (!day.isBefore(today)) {
                heading = new Heading("today", null, null);
            } else if (day.equals(today.minusDays(1))) {
                heading = new Heading("yesterday", null, null);
            } else if (!day.isBefore(weekStart)) {
                heading = new Heading("week", null, null);
            } else if (day.getYear() == today.getYear()) {
                heading = new Heading("month", YearMonth.from(day), null);
            } else {
                heading = new Heading("year", null, Year.of(day.getYear()));
            }
            if (!Objects.equals(heading, last)) {
                rows.add(heading);
                last = heading;
            }
            rows.add(new Entry(s));
        }
        return rows;
    }
    // --8<-- [end:of]
}

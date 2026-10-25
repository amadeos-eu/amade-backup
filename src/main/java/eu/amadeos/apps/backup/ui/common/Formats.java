package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.commons.i18n.Messages;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Sizes, counts, times and durations as the window shows them, in the user's language. */
public final class Formats {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private final Messages msg;
    private final Locale locale;
    private final ZoneId zone;

    public Formats(Messages msg, ZoneId zone) {
        this.msg = msg;
        this.locale = msg.locale();
        this.zone = zone;
    }

    public Messages messages() {
        return msg;
    }

    /** "214 MB", "3.1 GB", "38.9 GB" (powers of 1000, like the File Manager). */
    public String bytes(long bytes) {
        if (bytes < 1000) return NumberFormat.getIntegerInstance(locale).format(bytes) + " B";
        double value = bytes;
        int unit = 0;
        while (value >= 1000 && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }
        NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMaximumFractionDigits(value < 100 && unit >= 2 ? 1 : 0);
        f.setMinimumFractionDigits(0);
        return f.format(value) + " " + UNITS[unit];
    }

    public String count(long n) {
        return NumberFormat.getIntegerInstance(locale).format(n);
    }

    /** "Today 13:02", "Yesterday 21:00", "Fri 23 Oct 21:00", "23 Oct 2025 21:00". */
    public String when(Instant instant, Instant now) {
        ZonedDateTime t = instant.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        String time = DateTimeFormatter.ofPattern("HH:mm", locale).format(t);
        LocalDate day = t.toLocalDate();
        if (day.equals(today)) return msg.format("time.today", time);
        if (day.equals(today.minusDays(1))) return msg.format("time.yesterday", time);
        if (day.getYear() == today.getYear()) {
            return DateTimeFormatter.ofPattern(msg.get("time.pattern.this-year"), locale).format(t);
        }
        return DateTimeFormatter.ofPattern(msg.get("time.pattern.other-year"), locale).format(t);
    }

    /** "12 Oct 2026". */
    public String date(Instant instant) {
        return DateTimeFormatter.ofPattern(msg.get("time.pattern.date"), locale).format(instant.atZone(zone));
    }

    /** "35 s", "2 min", "1 h 05 min". */
    public String duration(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return msg.format("duration.seconds", s);
        if (s < 3600) return msg.format("duration.minutes", (s + 30) / 60);
        return msg.format("duration.hours", s / 3600, String.format("%02d", (s % 3600) / 60));
    }

    public ZoneId zone() {
        return zone;
    }
}

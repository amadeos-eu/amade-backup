package eu.amadeos.apps.backup.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TimelineTest {
    private static Snapshot at(String time) {
        return new Snapshot(time, time.substring(0, 8), Instant.parse(time), List.of("/home/ralf"), List.of());
    }

    private static String heading(Timeline.Row row) {
        Timeline.Heading h = assertInstanceOf(Timeline.Heading.class, row);
        return h.kind() + (h.month() != null ? " " + h.month() : "") + (h.year() != null ? " " + h.year() : "");
    }

    @Test
    void backupsAreGroupedNewestFirst() {
        // Sunday 25 October 2026; with Monday as the first day of the week, the week began on the 19th.
        LocalDate today = LocalDate.of(2026, 10, 25);
        List<Timeline.Row> rows = Timeline.of(List.of(at("2025-12-31T21:00:00Z"), at("2026-10-25T09:00:00Z"),
                at("2026-10-24T21:00:00Z"), at("2026-10-20T21:00:00Z"), at("2026-10-02T21:00:00Z"),
                at("2026-10-25T21:00:00Z"), at("2026-09-15T21:00:00Z")), today, ZoneOffset.UTC, Locale.GERMANY);
        assertEquals("today", heading(rows.get(0)));
        assertEquals(Instant.parse("2026-10-25T21:00:00Z"), ((Timeline.Entry) rows.get(1)).snapshot().time());
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), ((Timeline.Entry) rows.get(2)).snapshot().time());
        assertEquals("yesterday", heading(rows.get(3)));
        assertEquals("week", heading(rows.get(5)));
        assertEquals("month " + YearMonth.of(2026, 10), heading(rows.get(7)));
        assertEquals("month " + YearMonth.of(2026, 9), heading(rows.get(9)));
        assertEquals("year " + Year.of(2025), heading(rows.get(11)));
        assertEquals(13, rows.size());
    }

    @Test
    void noBackupsNoRows() {
        assertEquals(List.of(), Timeline.of(List.of(), LocalDate.of(2026, 1, 1), ZoneOffset.UTC, Locale.UK));
    }
}

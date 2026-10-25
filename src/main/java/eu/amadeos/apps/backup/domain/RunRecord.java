package eu.amadeos.apps.backup.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * One run of a job in its history. For a backup {@code added}, {@code changed} and {@code removed} count
 * files against the backup before; for a sync they count copies, updates and removals.
 *
 * @param trigger  how it started: "manual", "schedule", "plugged-in", "catch-up"
 * @param detail   the first problem, "" when there was none
 * @param snapshot the restic snapshot id of a backup, "" otherwise
 */
public record RunRecord(Instant started, Duration took, RunResult result, String trigger, long files, long added,
                        long changed, long removed, long bytes, String snapshot, String detail) {
    public RunRecord {
        trigger = trigger == null ? "manual" : trigger;
        snapshot = snapshot == null ? "" : snapshot;
        detail = detail == null ? "" : detail;
        // Milliseconds, as the history keeps it.
        took = took == null ? Duration.ZERO : took.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    public static RunRecord failed(Instant started, Duration took, String trigger, RunResult result, String detail) {
        return new RunRecord(started, took, result, trigger, 0, 0, 0, 0, 0, "", detail);
    }
}

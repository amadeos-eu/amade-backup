package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.Schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Whether a job is to run now, and why: the rules of the helper, without disks, threads or windows.
 * <ul>
 *     <li>A daily or weekly job runs once for each time of its schedule. If the computer was off or the disk
 *     absent at that time, it runs at the next chance ("catch-up"); a run that failed is not repeated before
 *     the next time, a run the user started counts as well.</li>
 *     <li>A job that is to run also when its disk is connected runs when the disk appears, unless it saved
 *     a backup less than {@link #PLUGGED_IN_GAP} ago.</li>
 * </ul>
 */
public final class Planner {
    /** Up to this long after its time a run counts as on time ("schedule"), later as "catch-up". */
    static final Duration ON_TIME = Duration.ofMinutes(15);
    /** A disk plugged in again within this time after a backup does not start another. */
    static final Duration PLUGGED_IN_GAP = Duration.ofHours(1);

    private Planner() {
    }

    // --8<-- [start:trigger]
    /**
     * @param runs       the job's history, newest first
     * @param knownSince when the helper first saw the job; times before it are not made up for a job that has
     *                   never run, so a new job does not start the moment it is saved
     * @param present    whether the job's disk or folders are there now
     * @param pluggedIn  whether they have just appeared
     * @return the trigger for the history ("schedule", "catch-up", "plugged-in"), or empty
     */
    public static Optional<String> trigger(Job job, List<RunRecord> runs, Instant knownSince, ZonedDateTime now,
                                           boolean present, boolean pluggedIn) {
        Schedule schedule = job.schedule();
        if (!schedule.automatic() || !present) return Optional.empty();
        Optional<ZonedDateTime> slot = schedule.lastSlot(now);
        if (slot.isPresent()) {
            Instant time = slot.get().toInstant();
            boolean attempted = runs.stream().anyMatch(r -> r.result() != RunResult.TARGET_MISSING
                    && !r.started().isBefore(time));
            boolean counts = !runs.isEmpty() || !time.isBefore(knownSince);
            if (!attempted && counts) {
                return Optional.of(Duration.between(time, now.toInstant()).compareTo(ON_TIME) <= 0
                        ? "schedule" : "catch-up");
            }
        }
        if (pluggedIn && schedule.whenPluggedIn()) {
            Optional<Instant> saved = runs.stream().filter(r -> r.result().saved()).map(RunRecord::started).findFirst();
            if (saved.isEmpty() || Duration.between(saved.get(), now.toInstant()).compareTo(PLUGGED_IN_GAP) >= 0) {
                return Optional.of("plugged-in");
            }
        }
        return Optional.empty();
    }
    // --8<-- [end:trigger]
}

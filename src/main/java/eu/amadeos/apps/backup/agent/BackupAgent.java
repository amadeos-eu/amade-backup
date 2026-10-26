package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The helper that starts with the session ({@code amade-backup --agent}): every {@link #tick()} it reads the
 * jobs, starts those that are due by their schedule or because their disk has just been connected, reminds
 * of backups whose disk has been away too long and checks each backup once a week. It runs the jobs one
 * after another on its own thread; the window and the helper keep out of each other's way through the job
 * locks and share the history.
 */
public final class BackupAgent {
    private static final Logger LOG = LoggerFactory.getLogger(BackupAgent.class);
    /** No reminders right after login: the disk may still be mounting, a catch-up run may still come. */
    static final Duration REMIND_AFTER_START = Duration.ofMinutes(5);
    /** At most one reminder per job in this time. */
    static final Duration REMIND_EVERY = Duration.ofHours(20);
    /** How often the backups of a job are checked (restic check, a part of the data read back). */
    static final Duration CHECK_EVERY = Duration.ofDays(7);
    static final int CHECK_READ_PERCENT = 5;

    private final JobStore jobs;
    private final HistoryStore history;
    private final Places places;
    private final BackupRunner backups;
    private final SyncRunner syncs;
    private final AgentState state;
    private final Power power;
    private final Notices notices;
    private final Messages msg;
    private final Formats formats;
    private final Clock clock;
    private final Executor runs;
    private final Instant started;
    private final Map<String, Boolean> present = new HashMap<>();
    /** Syncs whose preview waits for the user. */
    private final Set<String> waiting = new HashSet<>();
    private volatile Cancellation current = Cancellation.none();
    private volatile boolean stopped;
    private boolean lowBatteryLogged;

    /** @param runs the helper's own thread, on which {@link #tick()} runs too (an answered preview comes back on it) */
    public BackupAgent(JobStore jobs, HistoryStore history, Places places, BackupRunner backups, SyncRunner syncs,
                       AgentState state, Power power, Notices notices, Messages msg, Clock clock, Executor runs) {
        this.jobs = jobs;
        this.history = history;
        this.places = places;
        this.backups = backups;
        this.syncs = syncs;
        this.state = state;
        this.power = power;
        this.notices = notices;
        this.msg = msg;
        this.formats = new Formats(msg, clock.getZone());
        this.clock = clock;
        this.runs = runs;
        this.started = clock.instant();
    }

    /** The jobs that run by themselves. */
    public static List<Job> automatic(List<Job> jobs) {
        return jobs.stream().filter(j -> j.schedule().automatic()).toList();
    }

    // --8<-- [start:tick]
    /** One look at the jobs; false when none of them runs by itself any more, so the helper can end. */
    public synchronized boolean tick() {
        if (stopped) return false;
        List<Job> saved = jobs.load();
        state.retain(saved.stream().map(Job::id).toList());
        List<Job> all = automatic(saved);
        if (all.isEmpty()) {
            state.save();
            return false;
        }
        boolean low = power.lowBattery();
        for (Job job : all) {
            if (stopped) break;
            Instant known = state.knownSince(job.id(), clock.instant());
            boolean there = present(job);
            Boolean before = present.put(job.id(), there);
            boolean pluggedIn = before != null && !before && there;
            if (waiting.contains(job.id())) continue;
            ZonedDateTime now = ZonedDateTime.now(clock);
            Optional<String> trigger = Planner.trigger(job, history.runs(job.id()), known, now, there, pluggedIn);
            if (trigger.isPresent()) {
                if (low) {
                    if (!lowBatteryLogged) LOG.info("Battery low: {} waits for the power supply", job.name());
                    lowBatteryLogged = true;
                    continue;
                }
                run(job, trigger.get());
            } else if (!there) {
                remind(job);
            }
        }
        if (!low) lowBatteryLogged = false;
        state.save();
        return true;
    }
    // --8<-- [end:tick]

    /** Stops the running job (the session ends); the helper starts nothing more. */
    public void stop() {
        stopped = true;
        current.cancel();
    }

    /** Whether the job's disk or folders are there. */
    boolean present(Job job) {
        if (job.kind() == JobKind.BACKUP) return places.resolve(job.target()).isPresent();
        if (job.pairs().isEmpty()) return false;
        for (FolderPair pair : job.pairs()) {
            Path parent = pair.target().toAbsolutePath().getParent();
            boolean target = Files.isDirectory(pair.target()) || parent != null && Files.isDirectory(parent);
            if (!Files.isDirectory(pair.source()) || !target) return false;
        }
        return true;
    }

    private void run(Job job, String trigger) {
        LOG.info("Starting {} ({})", job.name(), trigger);
        current = new Cancellation();
        if (job.kind() == JobKind.BACKUP) {
            RunRecord record = backups.run(job, trigger, p -> { }, current);
            finished(job, record);
            if (record.result().saved()) checkWeekly(job);
        } else {
            sync(job, trigger);
        }
        current = Cancellation.none();
    }

    // --8<-- [start:sync]
    /**
     * A sync that only copies runs at once; one that would remove or replace files shows its preview first and
     * runs when the user says Sync Now, as when it is started by hand.
     */
    private void sync(Job job, String trigger) {
        List<SyncPlan> plans;
        try {
            plans = syncs.plan(job, current);
        } catch (IOException | EngineException e) {
            if (e instanceof EngineException ee && ee.kind() == EngineException.Kind.CANCELLED) return;
            record(job, RunRecord.failed(clock.instant(), Duration.ZERO, trigger, RunResult.FAILED, e.getMessage()));
            notices.problem(job, msg.format("agent.problem.compare", job.name(), e.getMessage()));
            return;
        }
        if (plans.stream().noneMatch(SyncPlan::deletesAnything)) {
            finished(job, syncs.apply(job, plans, trigger, p -> { }, current));
            return;
        }
        waiting.add(job.id());
        notices.offerSync(job, plans, yes -> runs.execute(() -> answered(job, plans, trigger, yes)));
    }
    // --8<-- [end:sync]

    private synchronized void answered(Job job, List<SyncPlan> plans, String trigger, boolean yes) {
        waiting.remove(job.id());
        if (stopped) return;
        if (yes) {
            current = new Cancellation();
            finished(job, syncs.apply(job, plans, trigger, p -> { }, current));
            current = Cancellation.none();
        } else {
            // Counts as this time's run, so that the preview does not come again at once
            record(job, RunRecord.failed(clock.instant(), Duration.ZERO, trigger, RunResult.CANCELLED, "cancelled"));
        }
    }

    private void record(Job job, RunRecord record) {
        try {
            history.add(job.id(), record, null);
        } catch (IOException e) {
            LOG.warn("Cannot write the history of {}", job.id(), e);
        }
    }

    /** Tells the user about a failed run; a run that worked, was stopped or found the job busy says nothing. */
    private void finished(Job job, RunRecord record) {
        LOG.info("{}: {} {}", job.name(), record.result(), record.detail());
        if (record.result() != RunResult.FAILED || record.detail().equals("running")) return;
        String key = "reason." + record.detail();
        String reason = msg.has(key) ? msg.get(key) : record.detail();
        notices.problem(job, msg.format("agent.problem.failed", job.name(), reason));
    }

    // --8<-- [start:remind]
    /** Once a day at most: the backup is overdue and its disk is not there. */
    private void remind(Job job) {
        if (job.kind() != JobKind.BACKUP) return;
        Instant now = clock.instant();
        if (Duration.between(started, now).compareTo(REMIND_AFTER_START) < 0) return;
        Schedule schedule = job.schedule();
        Optional<Instant> saved = history.lastSaved(job.id()).map(RunRecord::started);
        if (!schedule.overdue(saved, now)) return;
        Optional<Instant> reminded = state.reminded(job.id());
        if (reminded.isPresent() && Duration.between(reminded.get(), now).compareTo(REMIND_EVERY) < 0) return;
        state.setReminded(job.id(), now);
        notices.remind(job, reminder(job, saved, now));
    }
    // --8<-- [end:remind]

    String reminder(Job job, Optional<Instant> saved, Instant now) {
        boolean disk = job.target().onRemovableDisk();
        String place = disk ? job.target().diskLabel() : job.target().path().toString();
        String kind = disk ? "disk" : "folder";
        if (saved.isEmpty()) return msg.format("agent.remind." + kind + ".never", job.name(), place);
        return msg.format("agent.remind." + kind, job.name(), formats.when(saved.get(), now),
                Duration.between(saved.get(), now).toDays(), place);
    }

    /** After a backup: once a week, restic reads the backups' structure and a part of their data back. */
    private void checkWeekly(Job job) {
        Instant now = clock.instant();
        Instant last = state.checked(job.id()).orElse(state.knownSince(job.id(), now));
        if (Duration.between(last, now).compareTo(CHECK_EVERY) < 0) return;
        Optional<Repository> repository = backups.repository(job);
        if (repository.isEmpty()) return;
        try {
            List<String> problems = backups.engine().check(repository.get(), CHECK_READ_PERCENT, current);
            state.setChecked(job.id(), now);
            LOG.info("Checked {}: {} problems", job.name(), problems.size());
            if (!problems.isEmpty()) {
                notices.problem(job, msg.format("check.problems", job.name(), String.join("\n", problems)));
            }
        } catch (EngineException e) {
            LOG.warn("Cannot check {}: {}", job.name(), e.getMessage());
        }
    }
}

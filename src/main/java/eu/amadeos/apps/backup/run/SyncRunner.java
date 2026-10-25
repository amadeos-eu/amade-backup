package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.sync.Patterns;
import eu.amadeos.apps.backup.sync.SyncExecutor;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.sync.SyncPlanner;
import eu.amadeos.apps.backup.sync.SyncResult;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs a sync job in two steps: {@link #plan} works out the preview without changing anything, {@link #apply}
 * carries it out, with the before and after commands, and writes the run into the history.
 */
public class SyncRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SyncRunner.class);

    private final SyncPlanner planner;
    private final SyncExecutor executor;
    private final HistoryStore history;
    private final CommandRunner commands;
    private final Path home;
    private final Clock clock;

    public SyncRunner(SyncPlanner planner, SyncExecutor executor, HistoryStore history, CommandRunner commands, Path home,
                      Clock clock) {
        this.planner = planner;
        this.executor = executor;
        this.history = history;
        this.commands = commands;
        this.home = home;
        this.clock = clock;
    }

    /** One plan per folder pair; a pair whose target disk is not there fails with an {@link IOException}. */
    public List<SyncPlan> plan(Job job, Cancellation cancellation) throws IOException, EngineException {
        if (job.kind() != JobKind.SYNC) throw new IllegalArgumentException("Not a sync job: " + job.id());
        Patterns excludes = new Patterns(job.excludes(), home);
        List<SyncPlan> plans = new ArrayList<>();
        for (FolderPair pair : job.pairs()) {
            Path parent = pair.target().toAbsolutePath().getParent();
            if (!Files.isDirectory(pair.target()) && (parent == null || !Files.isDirectory(parent))) {
                throw new IOException("Target missing: " + pair.target());
            }
            plans.add(planner.plan(pair, job.sync(), excludes, cancellation));
        }
        return plans;
    }

    public RunRecord apply(Job job, List<SyncPlan> plans, String trigger, Consumer<BackupProgress> progress,
                           Cancellation cancellation) {
        Instant started = clock.instant();
        StringBuilder log = new StringBuilder();
        log.append("amade-backup sync ").append(job.name()).append(", ").append(started).append('\n');
        RunRecord record;
        Optional<JobLock> lock;
        try {
            lock = JobLock.tryAcquire(history.directory(job.id()));
        } catch (IOException e) {
            lock = Optional.empty();
        }
        if (lock.isEmpty()) return RunRecord.failed(started, Duration.ZERO, trigger, RunResult.FAILED, "running");
        try (JobLock held = lock.get()) {
            record = sync(job, plans, trigger, progress, cancellation, started, log);
        } catch (IOException e) {
            record = RunRecord.failed(started, Duration.between(started, clock.instant()), trigger, RunResult.FAILED,
                    e.getMessage());
        }
        log.append(record.result()).append(record.detail().isEmpty() ? "" : ": " + record.detail()).append('\n');
        try {
            history.add(job.id(), record, log.toString());
        } catch (IOException e) {
            LOG.warn("Cannot write the history of {}", job.id(), e);
        }
        return record;
    }

    private RunRecord sync(Job job, List<SyncPlan> plans, String trigger, Consumer<BackupProgress> progress,
                           Cancellation cancellation, Instant started, StringBuilder log) {
        if (!job.sync().before().isEmpty()) {
            boolean ok = command(job.sync().before(), log);
            if (!ok && job.sync().stopIfBeforeFails()) {
                return RunRecord.failed(started, Duration.between(started, clock.instant()), trigger, RunResult.FAILED,
                        "before-failed");
            }
        }
        SyncResult result = SyncResult.empty();
        long files = 0;
        try {
            for (SyncPlan plan : plans) {
                log.append(plan.pair().source()).append(" -> ").append(plan.pair().target()).append('\n');
                files += plan.actions().size();
                result = result.plus(executor.apply(plan, progress, cancellation));
            }
        } catch (EngineException e) {
            log.append("Cancelled\n");
            return new RunRecord(started, Duration.between(started, clock.instant()), RunResult.CANCELLED, trigger, files,
                    result.copied(), result.updated(), result.deleted(), result.bytes(), "", "cancelled");
        }
        result.errors().forEach(e -> log.append("Failed: ").append(e).append('\n'));
        if (!job.sync().after().isEmpty() && result.copied() + result.updated() + result.deleted() > 0) {
            command(job.sync().after(), log);
        }
        return new RunRecord(started, Duration.between(started, clock.instant()),
                result.errors().isEmpty() ? RunResult.OK : RunResult.INCOMPLETE, trigger, files, result.copied(),
                result.updated(), result.deleted(), result.bytes(), "",
                result.errors().isEmpty() ? "" : result.errors().getFirst());
    }

    /** Runs a before or after command with {@code sh -c}; its output goes into the log. */
    private boolean command(String command, StringBuilder log) {
        log.append("$ ").append(command).append('\n');
        try {
            ProgramResult result = commands.run(List.of("sh", "-c", command), Duration.ofMinutes(10));
            log.append(result.stdoutText()).append(result.stderrText());
            log.append("Exit code ").append(result.exitCode()).append('\n');
            return result.succeeded();
        } catch (IOException e) {
            log.append(e.getMessage()).append('\n');
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

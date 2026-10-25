package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.HomeLayout;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.BackupRequest;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.DiffStats;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Runs a backup job from start to end, for the window now and the background helper later: finds the disk,
 * takes the password from the keyring, saves the list of installed packages (home backups), backs up, thins
 * out old backups after the keep policy and writes the run into the history. It never throws; every ending
 * is a {@link RunRecord}.
 */
public class BackupRunner {
    private static final Logger LOG = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupEngine engine;
    private final Places places;
    private final Passwords passwords;
    private final HistoryStore history;
    private final CommandRunner commands;
    private final Path home;
    private final Path dataHome;
    private final Readme readme;
    private final Clock clock;

    public BackupRunner(BackupEngine engine, Places places, Passwords passwords, HistoryStore history,
                        CommandRunner commands, Path home, Path dataHome, Readme readme, Clock clock) {
        this.engine = engine;
        this.places = places;
        this.passwords = passwords;
        this.history = history;
        this.commands = commands;
        this.home = home;
        this.dataHome = dataHome;
        this.readme = readme;
        this.clock = clock;
    }

    public BackupEngine engine() {
        return engine;
    }

    /** The repository of the job if its disk is there and its password known. */
    public Optional<Repository> repository(Job job) {
        Optional<Path> location = places.resolve(job.target());
        Optional<String> password = passwords.lookup(job);
        if (location.isEmpty() || password.isEmpty()) return Optional.empty();
        return Optional.of(new Repository(location.get(), password.get()));
    }

    /** Creates the job's repository with {@code password}, keeps the password in the keyring and writes the README. */
    public Repository create(Job job, String password) throws EngineException {
        Path location = places.resolve(job.target()).orElseThrow(() -> new EngineException(EngineException.Kind.FAILED,
                "The target is not there: " + job.target().diskLabel() + " " + job.target().path()));
        Repository repository = new Repository(location, password);
        if (!engine.isRepository(location)) engine.init(repository);
        passwords.store(job, password);
        readme.write(job, location);
        return repository;
    }

    // --8<-- [start:run]
    public RunRecord run(Job job, String trigger, Consumer<BackupProgress> progress, Cancellation cancellation) {
        if (job.kind() != JobKind.BACKUP) throw new IllegalArgumentException("Not a backup job: " + job.id());
        Instant started = clock.instant();
        StringBuilder log = new StringBuilder();
        log.append("amade-backup ").append(job.name()).append(", ").append(started).append(", ").append(trigger).append('\n');
        RunRecord record;
        Optional<JobLock> lock;
        try {
            lock = JobLock.tryAcquire(history.directory(job.id()));
        } catch (IOException e) {
            lock = Optional.empty();
            LOG.warn("Cannot lock {}", job.id(), e);
        }
        if (lock.isEmpty()) {
            // Another run of the same job is busy; it writes its own history entry.
            return RunRecord.failed(started, Duration.ZERO, trigger, RunResult.FAILED, "running");
        }
        try (JobLock held = lock.get()) {
            record = backUp(job, trigger, progress, cancellation, started, log);
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

    private RunRecord backUp(Job job, String trigger, Consumer<BackupProgress> progress, Cancellation cancellation,
                             Instant started, StringBuilder log) {
        Optional<Path> location = places.resolve(job.target());
        if (location.isEmpty()) {
            return RunRecord.failed(started, Duration.ZERO, trigger, RunResult.TARGET_MISSING, job.target().diskLabel());
        }
        Optional<String> password = passwords.lookup(job);
        if (password.isEmpty()) {
            return RunRecord.failed(started, Duration.ZERO, trigger, RunResult.FAILED, "no-password");
        }
        Repository repository = new Repository(location.get(), password.get());
        try {
            if (!engine.isRepository(location.get())) {
                return RunRecord.failed(started, Duration.ZERO, trigger, RunResult.FAILED, "no-repository");
            }
            if (job.isHome()) savePackageList(log);
            List<String> excludes = excludes(job, location.get());
            log.append("Leaving out: ").append(String.join(", ", excludes)).append('\n');
            Optional<String> previous = history.lastSaved(job.id()).map(RunRecord::snapshot).filter(s -> !s.isEmpty());
            BackupSummary summary = engine.backup(repository, new BackupRequest(job.sources(), excludes, job.options(),
                    List.of("amadeos-job:" + job.id())), progress, cancellation);
            log.append(String.format("Saved %s: %d new, %d changed, %d unchanged files, %d bytes added%n",
                    summary.snapshotId(), summary.filesNew(), summary.filesChanged(), summary.filesUnmodified(),
                    summary.bytesAdded()));
            summary.errors().forEach(e -> log.append("Not readable: ").append(e).append('\n'));
            long removed = 0;
            if (previous.isPresent()) {
                try {
                    DiffStats diff = engine.diff(repository, previous.get(), summary.snapshotId());
                    removed = diff.removed();
                } catch (EngineException e) {
                    // the previous backup may have been thinned out already
                }
            }
            try {
                int forgotten = engine.forget(repository, job.keep(), cancellation);
                if (forgotten > 0) log.append("Thinned out ").append(forgotten).append(" older backups\n");
            } catch (EngineException e) {
                if (e.kind() == EngineException.Kind.CANCELLED) throw e;
                log.append("Thinning out failed: ").append(e.getMessage()).append('\n');
            }
            readme.write(job, location.get());
            RunResult result = summary.errors().isEmpty() ? RunResult.OK : RunResult.INCOMPLETE;
            return new RunRecord(started, Duration.between(started, clock.instant()), result, trigger,
                    summary.totalFiles(), summary.filesNew(), summary.filesChanged(), removed, summary.bytesAdded(),
                    summary.snapshotId(), summary.errors().isEmpty() ? "" : summary.errors().getFirst());
        } catch (EngineException e) {
            RunResult result = e.kind() == EngineException.Kind.CANCELLED ? RunResult.CANCELLED : RunResult.FAILED;
            log.append(e.kind()).append(": ").append(e.getMessage()).append('\n');
            return RunRecord.failed(started, Duration.between(started, clock.instant()), trigger, result,
                    e.kind() == EngineException.Kind.FAILED ? e.getMessage() : e.kind().key());
        }
    }
    // --8<-- [end:run]

    /**
     * The job's own patterns, the parts of the home folder that are switched off, caches and the trash, and the
     * repository itself when it lies inside a source.
     */
    List<String> excludes(Job job, Path repository) {
        List<String> excludes = new ArrayList<>(job.excludes());
        for (Path source : job.sources()) {
            Path abs = source.toAbsolutePath().normalize();
            if (abs.equals(home)) {
                for (String out : HomeLayout.ALWAYS_LEFT_OUT) excludes.add(home.resolve(out).toString());
            }
            if (repository.toAbsolutePath().normalize().startsWith(abs)) {
                excludes.add(repository.toAbsolutePath().normalize().toString());
            }
        }
        if (job.isHome()) {
            try {
                for (Path p : new HomeLayout(home).excludes(job.homeAreas())) {
                    String s = p.toString();
                    if (!excludes.contains(s)) excludes.add(s);
                }
            } catch (IOException e) {
                LOG.warn("Cannot read the home folder's layout", e);
            }
        }
        return excludes;
    }

    /**
     * {@code apt-mark showmanual} into {@code ~/.local/share/amadeos/backup/installed-packages.txt}, which the
     * home backup then contains: after a new installation, Software can put the programs back.
     */
    void savePackageList(StringBuilder log) {
        try {
            ProgramResult result = commands.run(List.of("apt-mark", "showmanual"), Duration.ofSeconds(30));
            if (!result.succeeded()) return;
            Path file = packageList();
            Files.createDirectories(file.getParent());
            Files.writeString(file, result.stdoutText(), StandardCharsets.UTF_8);
            log.append("Package list saved\n");
        } catch (IOException e) {
            // not on a Debian system
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path packageList() {
        return dataHome.resolve("amadeos/backup/installed-packages.txt");
    }
}

package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.bootstrap.BackupModule;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.engine.restic.ResticEngine;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.FinishedProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backups of a test home folder with the real restic, the way the main window runs them. */
class BackupRunnerTest {
    static final Messages EN = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.ENGLISH);
    static final Messages DE = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.GERMAN);

    @TempDir
    Path dir;
    Path home;
    Path disk;
    ResticEngine engine;
    Passwords passwords;
    HistoryStore history;
    BackupRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(dir.resolve("home/ralf"));
        disk = Files.createDirectories(dir.resolve("disk"));
        Files.createDirectories(home.resolve("Documents"));
        Files.writeString(home.resolve("Documents/letter.txt"), "Dear Ada");
        Files.createDirectories(home.resolve("Videos"));
        Files.writeString(home.resolve("Videos/film.mkv"), "big");
        Files.createDirectories(home.resolve(".cache"));
        Files.writeString(home.resolve(".cache/junk"), "junk");
        engine = new ResticEngine(CommandRunner.system());
        passwords = new Passwords(new SessionPasswordStore());
        history = new HistoryStore(dir.resolve("state"));
        CommandRunner apt = command -> FinishedProcess.printing("restic\nvim\n");
        runner = new BackupRunner(engine, new Places(new Disks(dir.resolve("no-mounts"), dir, dir)), passwords,
                history, apt, home, home.resolve(".local/share"), new Readme(DE, EN), Clock.systemUTC());
    }

    private Job homeJob() {
        return Job.home("home-folder", "Home folder", home, Target.folder(disk.resolve(Places.FOLDER).resolve("home-folder")),
                HomeArea.defaults(), List.of("*.tmp"));
    }

    @Test
    void aHomeBackupSavesWhatWasChosen() throws Exception {
        Job job = homeJob();
        Repository repo = runner.create(job, "correct horse battery");
        assertEquals(Optional.of("correct horse battery"), passwords.lookup(job));
        assertTrue(Files.exists(disk.resolve(Places.FOLDER).resolve(Readme.FILE)));
        assertTrue(Files.readString(disk.resolve(Places.FOLDER).resolve(Readme.FILE)).contains("restic --repo home-folder"));

        RunRecord first = runner.run(job, "manual", p -> { }, Cancellation.none());
        assertEquals(RunResult.OK, first.result(), first.detail());
        assertFalse(first.snapshot().isEmpty());
        assertEquals(Optional.of(first), history.last(job.id()));
        assertTrue(Files.readString(history.log(job.id())).contains("Leaving out"));
        assertEquals("restic\nvim\n", Files.readString(runner.packageList()));

        List<String> names = engine.list(repo, first.snapshot(), home.toString()).stream().map(SnapshotEntry::name).toList();
        assertTrue(names.contains("Documents"), names.toString());
        assertTrue(names.contains(".local"), "the package list is in the backup");
        assertFalse(names.contains("Videos"), "videos are off by default");
        assertFalse(names.contains(".cache"));

        Files.delete(home.resolve("Documents/letter.txt"));
        RunRecord second = runner.run(job, "schedule", p -> { }, Cancellation.none());
        assertEquals(1, second.removed());
        assertEquals(2, history.runs(job.id()).size());
    }

    @Test
    void aMissingDiskOrPasswordIsRecordedNotThrown() throws Exception {
        Job onDisk = Job.home("stick", "Stick", home, Target.onDisk("1234-ABCD", "STICK", Path.of("amadeos-backup/stick")),
                EnumSet.of(HomeArea.DOCUMENTS), List.of());
        RunRecord missing = runner.run(onDisk, "manual", p -> { }, Cancellation.none());
        assertEquals(RunResult.TARGET_MISSING, missing.result());
        assertEquals("STICK", missing.detail());

        Job job = homeJob();
        RunRecord noPassword = runner.run(job, "manual", p -> { }, Cancellation.none());
        assertEquals(RunResult.FAILED, noPassword.result());
        assertEquals("no-password", noPassword.detail());

        passwords.store(job, "a password");
        Files.createDirectories(disk.resolve(Places.FOLDER).resolve("home-folder"));
        assertEquals("no-repository", runner.run(job, "manual", p -> { }, Cancellation.none()).detail());
    }

    @Test
    void theSameJobDoesNotRunTwiceAtOnce() throws Exception {
        Job job = homeJob();
        runner.create(job, "correct horse battery");
        try (JobLock held = JobLock.tryAcquire(history.directory(job.id())).orElseThrow()) {
            RunRecord busy = runner.run(job, "schedule", p -> { }, Cancellation.none());
            assertEquals(RunResult.FAILED, busy.result());
            assertEquals("running", busy.detail());
        }
        assertEquals(RunResult.OK, runner.run(job, "manual", p -> { }, Cancellation.none()).result());
    }

    @Test
    void aBackupInsideItsOwnSourceLeavesItselfOut() {
        Path repo = home.resolve("Backups/amadeos-backup/docs");
        Job job = Job.backup("docs", "Docs", List.of(home), Target.folder(repo));
        List<String> excludes = runner.excludes(job, repo);
        assertTrue(excludes.contains(repo.toString()));
        assertTrue(excludes.contains(home.resolve(".cache").toString()));
    }
}

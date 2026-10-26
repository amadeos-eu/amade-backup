package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.bootstrap.BackupModule;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.engine.restic.ResticEngine;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.Readme;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.sync.SyncExecutor;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.sync.SyncPlanner;
import eu.amadeos.apps.backup.sync.Trash;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.FinishedProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The helper with the real restic, a clock that the test moves on and a recorder for its windows. */
class BackupAgentTest {
    static final Messages EN = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.ENGLISH);

    @TempDir
    Path dir;
    Path home;
    Path disk;
    Path sys;
    MovingClock clock;
    JobStore jobs;
    HistoryStore history;
    BackupRunner backups;
    Recorder notices;
    BackupAgent agent;
    List<Runnable> queued = new ArrayList<>();

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-28T20:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void pass(Duration d) {
            now = now.plus(d);
        }
    }

    static final class Recorder implements Notices {
        final List<String> reminders = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        final List<Consumer<Boolean>> previews = new ArrayList<>();

        @Override
        public void remind(Job job, String text) {
            reminders.add(text);
        }

        @Override
        public void problem(Job job, String text) {
            problems.add(text);
        }

        @Override
        public void offerSync(Job job, List<SyncPlan> plans, Consumer<Boolean> answer) {
            previews.add(answer);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(dir.resolve("home/ralf"));
        Files.createDirectories(home.resolve("Documents"));
        Files.writeString(home.resolve("Documents/letter.txt"), "Dear Ada");
        disk = dir.resolve("BACKUP");
        sys = Files.createDirectories(dir.resolve("power"));
        clock = new MovingClock();
        jobs = new JobStore(dir.resolve("config/jobs.json"));
        history = new HistoryStore(dir.resolve("state"));
        Places places = new Places(new Disks(dir.resolve("no-mounts"), dir, dir));
        CommandRunner apt = command -> FinishedProcess.printing("");
        backups = new BackupRunner(new ResticEngine(CommandRunner.system()), places,
                new Passwords(new SessionPasswordStore()), history, apt, home, home.resolve(".local/share"),
                new Readme(EN, EN), clock);
        Trash trash = Trash.forUser(home, home.resolve(".local/share"));
        SyncRunner syncs = new SyncRunner(new SyncPlanner(trash), new SyncExecutor(trash), history, apt, home, clock);
        notices = new Recorder();
        agent = new BackupAgent(jobs, history, places, backups, syncs, new AgentState(dir.resolve("state/agent.json")),
                new Power(sys), notices, EN, clock, queued::add);
    }

    /** A daily backup at 21:00 to the disk folder, with its repository made while the disk was there. */
    Job documents() throws Exception {
        Files.createDirectories(disk);
        Job job = Job.backup("docs", "Documents", List.of(home.resolve("Documents")),
                Target.folder(disk.resolve(Places.FOLDER).resolve("docs"))).withSchedule(Schedule.DEFAULT);
        jobs.put(job);
        backups.create(job, "correct horse battery");
        return job;
    }

    @Test
    void withoutJobsThatRunByThemselvesTheHelperEnds() throws IOException {
        assertFalse(agent.tick());
        jobs.put(PlannerTest.job(Schedule.MANUAL));
        assertFalse(agent.tick());
    }

    @Test
    void aDailyBackupRunsAtItsTimeAndOnlyOnce() throws Exception {
        documents();
        assertTrue(agent.tick());
        assertEquals(List.of(), history.runs("docs"));

        clock.pass(Duration.ofMinutes(61));
        agent.tick();
        List<RunRecord> runs = history.runs("docs");
        assertEquals(1, runs.size());
        assertEquals(RunResult.OK, runs.getFirst().result());
        assertEquals("schedule", runs.getFirst().trigger());

        clock.pass(Duration.ofMinutes(30));
        agent.tick();
        assertEquals(1, history.runs("docs").size());
        assertEquals(List.of(), notices.problems);
    }

    @Test
    void aBackupWhoseDiskIsAwayIsRemindedOfOnceADay() throws Exception {
        Job job = documents();
        RunRecord old = new RunRecord(clock.instant().minus(Duration.ofDays(3)), Duration.ofMinutes(1), RunResult.OK,
                "manual", 1, 1, 0, 0, 10, "abc", "");
        history.add(job.id(), old, null);
        Files.move(disk, dir.resolve("BACKUP-away"));

        agent.tick();
        assertEquals(List.of(), notices.reminders, "no reminder right after login");
        clock.pass(Duration.ofMinutes(10));
        agent.tick();
        assertEquals(1, notices.reminders.size());
        assertTrue(notices.reminders.getFirst().startsWith("The backup \"Documents\" was last made"),
                notices.reminders.getFirst());
        assertTrue(notices.reminders.getFirst().contains("3 days ago"), notices.reminders.getFirst());
        clock.pass(Duration.ofHours(2));
        agent.tick();
        assertEquals(1, notices.reminders.size());

        // The disk comes back: the missed 21:00 is made up
        Files.move(dir.resolve("BACKUP-away"), disk);
        agent.tick();
        assertEquals("catch-up", history.runs("docs").getFirst().trigger());
        assertEquals(RunResult.OK, history.runs("docs").getFirst().result());
    }

    @Test
    void nothingStartsOnANearlyEmptyBattery() throws Exception {
        documents();
        Files.createDirectories(sys.resolve("BAT0"));
        Files.writeString(sys.resolve("BAT0/type"), "Battery\n");
        Files.writeString(sys.resolve("BAT0/status"), "Discharging\n");
        Files.writeString(sys.resolve("BAT0/capacity"), "9\n");
        agent.tick();
        clock.pass(Duration.ofMinutes(61));
        agent.tick();
        assertEquals(List.of(), history.runs("docs"));
        Files.writeString(sys.resolve("BAT0/status"), "Charging\n");
        agent.tick();
        assertEquals(1, history.runs("docs").size());
    }

    @Test
    void aStickWithAnUpdateSyncIsFilledWhenItIsPluggedIn() throws Exception {
        Path music = Files.createDirectories(home.resolve("Music"));
        Files.writeString(music.resolve("song.ogg"), "la la");
        Path stick = dir.resolve("STICK");
        SyncOptions update = new SyncOptions(false, false, false, false, "", true, "");
        Job car = Job.sync("car", "Music for the car", List.of(new FolderPair(music, stick.resolve("Music"))), update)
                .withSchedule(new Schedule(Schedule.Frequency.MANUAL, Schedule.DEFAULT.time(), Schedule.DEFAULT.day(), true));
        jobs.put(car);

        agent.tick();
        assertEquals(List.of(), history.runs("car"));
        Files.createDirectories(stick);
        agent.tick();
        assertEquals("plugged-in", history.runs("car").getFirst().trigger());
        assertEquals("la la", Files.readString(stick.resolve("Music/song.ogg")));
        assertEquals(List.of(), notices.previews);
    }

    @Test
    void aMirrorThatWouldRemoveFilesAsksFirst() throws Exception {
        Path music = Files.createDirectories(home.resolve("Music"));
        Path stick = Files.createDirectories(dir.resolve("STICK/Music"));
        Files.writeString(stick.resolve("old.ogg"), "gone from the source");
        Job car = Job.sync("car", "Music for the car", List.of(new FolderPair(music, stick)), SyncOptions.DEFAULT)
                .withSchedule(Schedule.DEFAULT);
        jobs.put(car);
        history.add("car", new RunRecord(clock.instant().minus(Duration.ofDays(1)), Duration.ZERO, RunResult.OK,
                "manual", 0, 0, 0, 0, 0, "", ""), null);

        clock.pass(Duration.ofMinutes(61));
        agent.tick();
        assertEquals(1, notices.previews.size());
        assertTrue(Files.exists(stick.resolve("old.ogg")));
        agent.tick();
        assertEquals(1, notices.previews.size(), "one preview at a time");

        notices.previews.getFirst().accept(false);
        queued.forEach(Runnable::run);
        assertEquals(RunResult.CANCELLED, history.runs("car").getFirst().result());
        assertTrue(Files.exists(stick.resolve("old.ogg")));
        agent.tick();
        assertEquals(1, notices.previews.size(), "not again before the next time");
    }
}

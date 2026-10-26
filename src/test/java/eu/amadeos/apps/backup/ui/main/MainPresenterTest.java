package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.Repository;
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
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.apps.backup.ui.common.PasswordDialog;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.FinishedProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static eu.amadeos.apps.backup.TestTexts.DE;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The main window's logic against a fake window, with real restic and real folders. */
class MainPresenterTest {
    /** Runs background work at once, so each action is finished when it returns. */
    static final class Inline extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** Remembers what the presenter showed and answers its questions as told. */
    static final class FakeView implements MainView {
        List<JobRow> rows = List.of();
        Detail detail;
        List<RunRecord> runs = List.of();
        final List<String> questions = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> infos = new ArrayList<>();
        boolean answer;
        boolean startSync;
        Optional<HomeAssistant.Result> assistant = Optional.empty();
        Optional<PasswordDialog.Answer> password = Optional.empty();
        List<SyncPlan> previewed;
        Repository restoring;

        public void showJobs(List<JobRow> rows, int selected) { this.rows = rows; }
        public void showDetail(Detail detail) { this.detail = detail; }
        public void showHistory(Job job, List<RunRecord> runs) { this.runs = runs; }
        public void showStatus(String text) { }
        public void setBusy(boolean busy) { }
        public Optional<HomeAssistant.Result> homeAssistant() { return assistant; }
        public Optional<Job> editJob(Job job, boolean isNew) { return Optional.of(job); }
        public Optional<String> newPassword(Job job) { return Optional.of("a long password"); }
        public Optional<PasswordDialog.Answer> askPassword(Job job, String problem) {
            if (problem != null) questions.add(problem);
            Optional<PasswordDialog.Answer> a = password;
            password = Optional.empty();
            return a;
        }
        public Optional<Path> chooseBackupFolder() { return Optional.empty(); }
        public boolean confirmSync(Job job, List<SyncPlan> plans) { previewed = plans; return startSync; }
        public void openRestore(Job job, Repository repository) { restoring = repository; }
        public Progress progress(String title, String heading, Cancellation cancellation) {
            return new Progress() {
                public void update(BackupProgress progress) { }
                public void close() { }
            };
        }
        public void printRecoverySheet(Job job, String where, String password) { }
        public boolean confirm(String title, String question) { questions.add(question); return answer; }
        public void showInfo(String text) { infos.add(text); }
        public void showError(String text) { errors.add(text); }
        public void showLog(String title, String text) { }
        public void showAbout(String engineVersion) { }
        public void close() { }
    }

    @TempDir
    Path dir;
    Path home;
    FakeView view;
    Passwords passwords;
    JobStore jobs;
    HistoryStore history;
    MainPresenter presenter;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(dir.resolve("disk"));
        Files.createDirectories(home.resolve("Documents"));
        Files.writeString(home.resolve("Documents/letter.txt"), "Dear Ada");
        view = new FakeView();
        passwords = new Passwords(new SessionPasswordStore());
        jobs = new JobStore(dir.resolve("config/jobs.json"));
        history = new HistoryStore(dir.resolve("state"));
        Places places = new Places(new Disks(dir.resolve("no-mounts"), dir, dir));
        ResticEngine engine = new ResticEngine(CommandRunner.system());
        BackupRunner backups = new BackupRunner(engine, places, passwords, history,
                command -> FinishedProcess.failing(1, ""), home, home.resolve(".local/share"), new Readme(EN, EN),
                Clock.systemUTC());
        Trash trash = new Trash(dir.resolve("trash"), dir, "1000");
        SyncRunner syncs = new SyncRunner(new SyncPlanner(trash), new SyncExecutor(trash), history,
                CommandRunner.system(), home, Clock.systemUTC());
        presenter = new MainPresenter(view, EN, jobs, history, places, passwords, backups, syncs, null, new Inline(),
                Runnable::run, Clock.system(ZoneOffset.UTC), home, () -> { });
    }

    private Job homeJob() {
        return Job.home("home-folder", "Home folder", home, Target.folder(dir.resolve("disk/amadeos-backup/home-folder")),
                HomeArea.defaults(), List.of());
    }

    @Test
    void theFirstStartOffersTheHomeBackup() {
        presenter.start();
        assertEquals(List.of(EN.get("first.question")), view.questions);
        assertEquals(List.of(), view.rows);
        assertEquals(null, view.detail);
    }

    @Test
    void theAssistantsBackupIsCreatedAndRunAtOnce() {
        view.assistant = Optional.of(new HomeAssistant.Result(homeJob(), "a long password", true));
        presenter.newHomeBackup();
        assertEquals(List.of(), view.errors);
        assertEquals(1, view.rows.size());
        assertEquals(MainView.State.OK, view.rows.getFirst().state());
        assertEquals("Backup to " + dir.resolve("disk/amadeos-backup/home-folder"), view.rows.getFirst().subtitle());
        assertTrue(view.detail.headline().startsWith("Backed up Today "), view.detail.headline());
        assertEquals("1 backups, encrypted and compressed", view.detail.subline());
        assertEquals(RunResult.OK, view.runs.getFirst().result());
        assertTrue(passwords.lookup(homeJob()).isPresent());
    }

    @Test
    void aForgottenPasswordIsAskedForAndCheckedBeforeRestoring() {
        view.assistant = Optional.of(new HomeAssistant.Result(homeJob(), "the right one", false));
        presenter.newHomeBackup();
        passwords.delete(homeJob());
        view.password = Optional.of(new PasswordDialog.Answer("a wrong one", false));
        presenter.restore();
        // the wrong password was refused and asked again; the fake then gives up
        assertEquals(List.of(EN.get("ask-password.wrong")), view.questions);
        assertEquals(null, view.restoring);

        view.password = Optional.of(new PasswordDialog.Answer("the right one", true));
        presenter.restore();
        assertEquals("the right one", view.restoring.password());
        assertEquals(Optional.of("the right one"), passwords.lookup(homeJob()));
    }

    @Test
    void aSyncShowsItsPreviewFirst() throws Exception {
        Path stick = Files.createDirectories(dir.resolve("stick"));
        jobs.put(Job.sync("docs", "Docs", List.of(new FolderPair(home.resolve("Documents"), stick.resolve("Documents"))),
                SyncOptions.DEFAULT));
        presenter.start();
        view.startSync = false;
        presenter.backUpNow();
        assertEquals(1, view.previewed.getFirst().count(eu.amadeos.apps.backup.sync.SyncAction.Kind.COPY));
        assertFalse(Files.exists(stick.resolve("Documents/letter.txt")));

        view.startSync = true;
        presenter.backUpNow();
        assertEquals("Dear Ada", Files.readString(stick.resolve("Documents/letter.txt")));
        assertEquals(MainView.State.OK, view.rows.getFirst().state());
    }

    @Test
    void deletingAJobAsksAndKeepsTheBackups() throws Exception {
        view.assistant = Optional.of(new HomeAssistant.Result(homeJob(), "a long password", true));
        presenter.newHomeBackup();
        view.answer = false;
        presenter.delete();
        assertEquals(1, jobs.load().size());
        view.answer = true;
        presenter.delete();
        assertEquals(List.of(), jobs.load());
        assertEquals(List.of(), view.rows);
        assertTrue(Files.isDirectory(dir.resolve("disk/amadeos-backup/home-folder")));
    }

    @Test
    void aMissingDiskIsShownAsWarningWithItsName() throws Exception {
        Job onStick = Job.home("stick", "Stick", home, Target.onDisk("1234", "STICK", Path.of("amadeos-backup/stick")),
                HomeArea.defaults(), List.of());
        jobs.put(onStick);
        history.add("stick", RunRecord.failed(Instant.now(), java.time.Duration.ZERO, "schedule", RunResult.TARGET_MISSING,
                "STICK"), null);
        presenter.start();
        assertEquals(MainView.State.WARNING, view.rows.getFirst().state());
        assertEquals("Connect Disk STICK to back up or restore.", view.detail.subline());
        presenter.backUpNow();
        assertEquals(List.of("Disk STICK is not connected."), view.errors);
    }

    @Test
    void theGermanListLine() throws Exception {
        jobs.put(homeJob());
        MainPresenter german = new MainPresenter(view, DE, jobs, history, new Places(new Disks(dir, dir, dir)),
                passwords, null, null, null, new Inline(), Runnable::run, Clock.systemUTC(), home, () -> { });
        assertEquals("Noch nicht gelaufen", german.detail(homeJob(), null).headline());
    }
}

package eu.amadeos.apps.backup.ui.main;

import com.google.inject.Inject;
import eu.amadeos.apps.backup.bootstrap.AmadeBackupApplication;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.apps.backup.ui.common.Describe;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.common.PasswordDialog;
import eu.amadeos.commons.app.Ui;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The main window's logic: the list of jobs with their state, the selected job's settings and history, and
 * the actions of the toolbar and menus. Everything slow (restic, disks, syncs) runs on the background executor;
 * the view is only touched on the {@link Ui} executor.
 */
public class MainPresenter {
    private static final Logger LOG = LoggerFactory.getLogger(MainPresenter.class);

    private final MainView view;
    private final Messages msg;
    private final Formats formats;
    private final JobStore jobs;
    private final HistoryStore history;
    private final Places places;
    private final Passwords passwords;
    private final BackupRunner backups;
    private final SyncRunner syncs;
    private final LocaleSettings locales;
    private final ExecutorService background;
    private final Executor ui;
    private final Clock clock;
    private final Path home;
    private final Runnable exit;

    private List<Job> list = List.of();
    private int selected = -1;
    private String engineVersion = "";
    private boolean busy;

    @Inject
    public MainPresenter(MainView view, Messages msg, JobStore jobs, HistoryStore history, Places places,
                         Passwords passwords, BackupRunner backups, SyncRunner syncs, LocaleSettings locales,
                         ExecutorService background, @Ui Executor ui, Clock clock, XdgDirectories xdg) {
        this(view, msg, jobs, history, places, passwords, backups, syncs, locales, background, ui, clock, xdg.home(),
                () -> System.exit(0));
    }

    public MainPresenter(MainView view, Messages msg, JobStore jobs, HistoryStore history, Places places,
                         Passwords passwords, BackupRunner backups, SyncRunner syncs, LocaleSettings locales,
                         ExecutorService background, Executor ui, Clock clock, Path home, Runnable exit) {
        this.view = view;
        this.msg = msg;
        this.formats = new Formats(msg, clock.getZone());
        this.jobs = jobs;
        this.history = history;
        this.places = places;
        this.passwords = passwords;
        this.backups = backups;
        this.syncs = syncs;
        this.locales = locales;
        this.background = background;
        this.ui = ui;
        this.clock = clock;
        this.home = home;
        this.exit = exit;
    }

    public Formats formats() {
        return formats;
    }

    public void start() {
        reload(null);
        background.execute(() -> {
            String status;
            try {
                engineVersion = backups.engine().version();
                status = msg.format("status.engine", engineVersion);
            } catch (EngineException e) {
                status = e.kind() == EngineException.Kind.PROGRAM_MISSING ? msg.get("status.no-restic")
                        : msg.format("status.engine-failed", e.getMessage());
            }
            String text = status;
            ui.execute(() -> view.showStatus(text));
        });
        if (list.isEmpty()) ui.execute(this::firstStart);
    }

    /** Without any job, the home backup is offered at once. */
    private void firstStart() {
        if (view.confirm(msg.get("first.title"), msg.get("first.question"))) newHomeBackup();
    }

    // ---- list and selection ----------------------------------------------------------------------------------

    void reload(String selectId) {
        list = jobs.load();
        if (selectId != null) {
            selected = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(selectId)) selected = i;
        }
        if (selected >= list.size()) selected = list.size() - 1;
        if (selected < 0 && !list.isEmpty()) selected = 0;
        List<MainView.JobRow> rows = new ArrayList<>();
        for (Job job : list) rows.add(row(job));
        view.showJobs(rows, selected);
        showSelected();
    }

    public void select(int index) {
        if (index == selected || index < 0 || index >= list.size()) return;
        selected = index;
        showSelected();
    }

    public Optional<Job> selectedJob() {
        return selected >= 0 && selected < list.size() ? Optional.of(list.get(selected)) : Optional.empty();
    }

    private void showSelected() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty()) {
            view.showDetail(null);
            view.showHistory(null, List.of());
            return;
        }
        view.showDetail(detail(job.get(), null));
        view.showHistory(job.get(), history.runs(job.get().id()));
        if (job.get().kind() == JobKind.BACKUP) countBackups(job.get());
    }

    /** The number of backups on the disk, read in the background when the disk is there. */
    private void countBackups(Job job) {
        Optional<Repository> repository = backups.repository(job);
        if (repository.isEmpty()) return;
        background.execute(() -> {
            try {
                int count = backups.engine().snapshots(repository.get()).size();
                ui.execute(() -> {
                    if (selectedJob().map(j -> j.id().equals(job.id())).orElse(false)) view.showDetail(detail(job, count));
                });
            } catch (EngineException e) {
                LOG.info("Cannot count the backups of {}: {}", job.id(), e.getMessage());
            }
        });
    }

    MainView.JobRow row(Job job) {
        String where = job.kind() == JobKind.BACKUP ? targetName(job.target())
                : job.pairs().isEmpty() ? "" : targetName(places.targetFor(job.pairs().getFirst().target()));
        String subtitle = msg.format("list.subtitle." + job.kind().key(), where);
        return new MainView.JobRow(job, state(job), subtitle);
    }

    String targetName(Target target) {
        if (target == null) return "";
        return target.onRemovableDisk() ? msg.format("target.disk", target.diskLabel()) : target.path().toString();
    }

    MainView.State state(Job job) {
        Optional<RunRecord> last = history.last(job.id());
        if (last.isEmpty()) return MainView.State.IDLE;
        RunRecord r = last.get();
        return switch (r.result()) {
            case OK -> overdue(job) ? MainView.State.WARNING : MainView.State.OK;
            case INCOMPLETE, TARGET_MISSING, CANCELLED -> MainView.State.WARNING;
            case FAILED -> MainView.State.PROBLEM;
        };
    }

    /** No saved run for longer than the schedule allows (2 days for daily, 9 for weekly). */
    boolean overdue(Job job) {
        Optional<RunRecord> saved = history.lastSaved(job.id());
        if (saved.isEmpty()) return true;
        Duration age = Duration.between(saved.get().started(), clock.instant());
        return switch (job.schedule().frequency()) {
            case DAILY -> age.toDays() >= 2;
            case WEEKLY -> age.toDays() >= 9;
            case MANUAL -> false;
        };
    }

    MainView.Detail detail(Job job, Integer backupCount) {
        Instant now = clock.instant();
        Optional<RunRecord> last = history.last(job.id());
        Optional<RunRecord> saved = history.lastSaved(job.id());
        MainView.State state = state(job);
        String headline;
        if (last.isEmpty()) {
            headline = msg.get("detail.never");
        } else {
            RunRecord r = last.get();
            String when = formats.when(r.started(), now);
            headline = switch (r.result()) {
                case OK -> overdue(job)
                        ? msg.format("detail.overdue", formats.when(saved.get().started(), now),
                        Duration.between(saved.get().started(), now).toDays())
                        : msg.format("detail.ok." + job.kind().key(), when, formats.duration(r.took()), formats.bytes(r.bytes()));
                case INCOMPLETE -> msg.format("detail.incomplete", when, r.detail());
                case FAILED -> msg.format("detail.failed", when, reason(r.detail()));
                case TARGET_MISSING -> msg.format("detail.missing", when, r.detail());
                case CANCELLED -> msg.format("detail.cancelled", when);
            };
        }
        List<String[]> facts = new ArrayList<>();
        String subline;
        if (job.kind() == JobKind.BACKUP) {
            boolean present = places.resolve(job.target()).isPresent();
            subline = !present ? msg.format("detail.target-absent", targetName(job.target()))
                    : backupCount == null ? msg.get("detail.encrypted")
                    : msg.format("detail.encrypted-count", backupCount);
            facts.add(new String[]{msg.get("fact.what"), what(job)});
            facts.add(new String[]{msg.get("fact.where"), where(job)});
            facts.add(new String[]{msg.get("fact.when"), when(job.schedule())});
            facts.add(new String[]{msg.get("fact.keep"), keep(job.keep())});
            facts.add(new String[]{msg.get("fact.password"), passwords.lookup(job).isPresent()
                    ? msg.get("fact.password.keyring") : msg.get("fact.password.missing")});
        } else {
            subline = job.sync().mirror() ? msg.get("detail.sync.mirror") : msg.get("detail.sync.update");
            for (FolderPair pair : job.pairs()) {
                facts.add(new String[]{facts.isEmpty() ? msg.get("fact.folders") : "", msg.format("fact.pair",
                        pair.source(), pair.target())});
            }
            if (!job.excludes().isEmpty()) {
                facts.add(new String[]{msg.get("fact.left-out"), String.join(", ", job.excludes())});
            }
            facts.add(new String[]{msg.get("fact.when"), when(job.schedule())});
        }
        return new MainView.Detail(job, state, headline, subline, facts);
    }

    private String reason(String detail) {
        String key = "reason." + detail;
        return msg.has(key) ? msg.get(key) : detail;
    }

    String what(Job job) {
        if (job.isHome()) {
            List<String> off = new ArrayList<>();
            for (HomeArea area : HomeArea.values()) {
                if (!job.homeAreas().contains(area)) off.add(msg.get("area." + area.key()));
            }
            String text = off.isEmpty() ? msg.format("fact.what.home-all", home)
                    : msg.format("fact.what.home-without", home, String.join(", ", off));
            return job.excludes().isEmpty() ? text : text + msg.format("fact.what.patterns", job.excludes().size());
        }
        List<String> names = job.sources().stream().map(Path::toString).toList();
        return String.join(", ", names);
    }

    String where(Job job) {
        Optional<Path> place = places.resolve(job.target());
        String base = targetName(job.target());
        if (job.target().onRemovableDisk()) base += msg.format("fact.where.folder", job.target().path());
        if (place.isPresent()) {
            long free = eu.amadeos.apps.backup.sync.FileSystemInfo.usableSpace(place.get());
            if (free != Long.MAX_VALUE) base += msg.format("fact.where.free", formats.bytes(free));
        } else {
            base += msg.get("fact.where.absent");
        }
        return base;
    }

    String when(Schedule s) {
        return Describe.schedule(msg, s);
    }

    String keep(KeepPolicy k) {
        return Describe.keep(msg, k);
    }

    // ---- actions -----------------------------------------------------------------------------------------------

    public void newHomeBackup() {
        Optional<HomeAssistant.Result> result = view.homeAssistant();
        if (result.isEmpty()) return;
        Job job = result.get().job();
        createBackup(job, result.get().password(), result.get().startNow());
    }

    public void newBackup() {
        Job draft = Job.backup(JobStore.newId(msg.get("new.backup.name"), list), msg.get("new.backup.name"),
                List.of(home.resolve("Documents")), Target.folder(home.resolve("Backups").resolve(Places.FOLDER)));
        Optional<Job> edited = view.editJob(draft, true);
        if (edited.isEmpty()) return;
        Job job = edited.get();
        Optional<String> password = view.newPassword(job);
        if (password.isEmpty()) return;
        createBackup(job, password.get(), view.confirm(msg.get("new.start.title"), msg.get("new.start.question")));
    }

    public void newSync() {
        Job draft = Job.sync(JobStore.newId(msg.get("new.sync.name"), list), msg.get("new.sync.name"), List.of(),
                SyncOptions.DEFAULT);
        Optional<Job> edited = view.editJob(draft, true);
        if (edited.isEmpty()) return;
        save(edited.get());
    }

    private void createBackup(Job job, String password, boolean startNow) {
        if (!save(job)) return;
        setBusy(true);
        background.execute(() -> {
            try {
                backups.create(job, password);
                ui.execute(() -> {
                    setBusy(false);
                    reload(job.id());
                    if (startNow) backUpNow();
                });
            } catch (EngineException e) {
                ui.execute(() -> {
                    setBusy(false);
                    reload(job.id());
                    view.showError(msg.format("error.create", job.name(), engineMessage(e)));
                });
            }
        });
    }

    private boolean save(Job job) {
        try {
            jobs.put(job);
            reload(job.id());
            return true;
        } catch (IOException e) {
            view.showError(msg.format("error.save", jobs.file(), e.getMessage()));
            return false;
        }
    }

    public void edit() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty()) return;
        view.editJob(job.get(), false).ifPresent(this::save);
    }

    public void delete() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty()) return;
        String question = msg.format("delete.question." + job.get().kind().key(), job.get().name());
        if (!view.confirm(msg.get("delete.title"), question)) return;
        try {
            jobs.remove(job.get().id());
            history.removeJob(job.get().id());
        } catch (IOException e) {
            view.showError(msg.format("error.save", jobs.file(), e.getMessage()));
        }
        reload(null);
    }

    public void backUpNow() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty() || busy) return;
        if (job.get().kind() == JobKind.SYNC) {
            syncNow(job.get());
            return;
        }
        withRepository(job.get(), repository -> {
            Cancellation cancellation = new Cancellation();
            MainView.Progress progress = view.progress(msg.get("progress.backup.title"),
                    msg.format("progress.backup.heading", job.get().name(), targetName(job.get().target())), cancellation);
            setBusy(true);
            background.execute(() -> {
                RunRecord record = backups.run(job.get(), "manual", progress::update, cancellation);
                ui.execute(() -> {
                    progress.close();
                    setBusy(false);
                    reload(job.get().id());
                    report(job.get(), record);
                });
            });
        });
    }

    private void report(Job job, RunRecord record) {
        switch (record.result()) {
            case OK, CANCELLED -> { }
            case INCOMPLETE -> view.showInfo(msg.format("report.incomplete", job.name(), record.detail()));
            case FAILED -> view.showError(msg.format("report.failed", job.name(), reason(record.detail())));
            case TARGET_MISSING -> view.showError(msg.format("report.missing", targetName(job.target())));
        }
    }

    private void syncNow(Job job) {
        Cancellation cancellation = new Cancellation();
        MainView.Progress progress = view.progress(msg.get("progress.compare.title"),
                msg.format("progress.compare.heading", job.name()), cancellation);
        setBusy(true);
        background.execute(() -> {
            try {
                List<SyncPlan> plans = syncs.plan(job, cancellation);
                ui.execute(() -> {
                    progress.close();
                    setBusy(false);
                    if (view.confirmSync(job, plans)) applySync(job, plans);
                });
            } catch (IOException | EngineException e) {
                ui.execute(() -> {
                    progress.close();
                    setBusy(false);
                    if (!(e instanceof EngineException ee && ee.kind() == EngineException.Kind.CANCELLED)) {
                        view.showError(msg.format("error.compare", job.name(), e.getMessage()));
                    }
                });
            }
        });
    }

    private void applySync(Job job, List<SyncPlan> plans) {
        Cancellation cancellation = new Cancellation();
        MainView.Progress progress = view.progress(msg.get("progress.sync.title"),
                msg.format("progress.sync.heading", job.name()), cancellation);
        setBusy(true);
        background.execute(() -> {
            RunRecord record = syncs.apply(job, plans, "manual", progress::update, cancellation);
            ui.execute(() -> {
                progress.close();
                setBusy(false);
                reload(job.id());
                if (record.result() == RunResult.INCOMPLETE) {
                    view.showInfo(msg.format("report.sync-incomplete", job.name(), record.detail()));
                } else if (record.result() == RunResult.FAILED) {
                    view.showError(msg.format("report.failed", job.name(), reason(record.detail())));
                }
            });
        });
    }

    public void restore() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty() || job.get().kind() != JobKind.BACKUP) return;
        withRepository(job.get(), repository -> view.openRestore(job.get(), repository));
    }

    public void check() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty() || job.get().kind() != JobKind.BACKUP || busy) return;
        withRepository(job.get(), repository -> {
            Cancellation cancellation = new Cancellation();
            MainView.Progress progress = view.progress(msg.get("progress.check.title"),
                    msg.format("progress.check.heading", job.get().name()), cancellation);
            setBusy(true);
            background.execute(() -> {
                String text;
                boolean ok;
                try {
                    List<String> problems = backups.engine().check(repository, 5, cancellation);
                    ok = problems.isEmpty();
                    text = ok ? msg.format("check.ok", job.get().name())
                            : msg.format("check.problems", job.get().name(), String.join("\n", problems));
                } catch (EngineException e) {
                    ok = e.kind() == EngineException.Kind.CANCELLED;
                    text = ok ? null : msg.format("check.failed", engineMessage(e));
                }
                String message = text;
                boolean good = ok;
                ui.execute(() -> {
                    progress.close();
                    setBusy(false);
                    if (message != null) {
                        if (good) view.showInfo(message); else view.showError(message);
                    }
                });
            });
        });
    }

    public void printRecoverySheet() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty() || job.get().kind() != JobKind.BACKUP) return;
        Optional<String> password = passwords.lookup(job.get());
        if (password.isEmpty()) {
            view.showError(msg.get("error.no-password-for-sheet"));
            return;
        }
        view.printRecoverySheet(job.get(), where(job.get()), password.get());
    }

    public void showLog() {
        Optional<Job> job = selectedJob();
        if (job.isEmpty()) return;
        Path log = history.log(job.get().id());
        String text;
        try {
            text = Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : msg.get("log.none");
        } catch (IOException e) {
            text = e.getMessage();
        }
        view.showLog(msg.format("log.title", job.get().name()), text);
    }

    /**
     * Open Existing Backup: a backup made on this or another computer, e.g. before a new installation. Finds the
     * repositories in the chosen folder, asks for the password and adds a job for each, then opens Restore.
     */
    public void openExisting() {
        Optional<Path> folder = view.chooseBackupFolder();
        if (folder.isEmpty()) return;
        List<Path> found = repositoriesIn(folder.get(), backups.engine());
        if (found.isEmpty()) {
            view.showError(msg.format("open.none", folder.get()));
            return;
        }
        Path location = found.getFirst();
        String name = location.getFileName().toString();
        String id = JobStore.newId(name, list);
        Job draft = Job.backup(id, name, List.of(home), places.targetFor(location)).withSchedule(Schedule.MANUAL);
        Optional<PasswordDialog.Answer> answer = view.askPassword(draft, null);
        if (answer.isEmpty()) return;
        setBusy(true);
        background.execute(() -> {
            try {
                Repository repository = new Repository(location, answer.get().password());
                List<Snapshot> snapshots = backups.engine().snapshots(repository);
                List<Path> sources = snapshots.isEmpty() ? List.of(home)
                        : snapshots.getLast().paths().stream().map(Path::of).toList();
                Job job = sources.equals(List.of(home))
                        ? Job.home(id, msg.get("open.home-name"), home, draft.target(), EnumSet.allOf(HomeArea.class),
                        List.of()).withSchedule(Schedule.MANUAL)
                        : draft.withSources(sources);
                ui.execute(() -> {
                    setBusy(false);
                    if (!save(job)) return;
                    if (answer.get().remember()) passwords.store(job, answer.get().password());
                    reload(job.id());
                    view.openRestore(job, repository);
                });
            } catch (EngineException e) {
                ui.execute(() -> {
                    setBusy(false);
                    view.showError(msg.format("open.failed", engineMessage(e)));
                });
            }
        });
    }

    /** The folder itself if it is a repository, else the repositories one or two levels below it. */
    static List<Path> repositoriesIn(Path folder, BackupEngine engine) {
        List<Path> found = new ArrayList<>();
        if (engine.isRepository(folder)) return List.of(folder);
        collect(folder, engine, found, 2);
        return found;
    }

    private static void collect(Path folder, BackupEngine engine, List<Path> found, int depth) {
        if (depth == 0 || !Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder, Files::isDirectory)) {
            List<Path> sorted = new ArrayList<>();
            children.forEach(sorted::add);
            sorted.sort(null);
            for (Path child : sorted) {
                if (engine.isRepository(child)) found.add(child); else collect(child, engine, found, depth - 1);
            }
        } catch (IOException e) {
            // unreadable
        }
    }

    /**
     * Runs {@code action} with the job's repository: the disk must be there, the password comes from the keyring
     * or is asked for (and checked) when it is missing or wrong.
     */
    private void withRepository(Job job, Consumer<Repository> action) {
        Optional<Path> location = places.resolve(job.target());
        if (location.isEmpty()) {
            view.showError(msg.format("report.missing", targetName(job.target())));
            return;
        }
        Optional<String> stored = passwords.lookup(job);
        if (stored.isPresent()) {
            action.accept(new Repository(location.get(), stored.get()));
            return;
        }
        askAndCheck(job, location.get(), null, action);
    }

    private void askAndCheck(Job job, Path location, String problem, Consumer<Repository> action) {
        Optional<PasswordDialog.Answer> answer = view.askPassword(job, problem);
        if (answer.isEmpty()) return;
        Repository repository = new Repository(location, answer.get().password());
        setBusy(true);
        background.execute(() -> {
            try {
                backups.engine().snapshots(repository);
                ui.execute(() -> {
                    setBusy(false);
                    if (answer.get().remember()) passwords.store(job, answer.get().password());
                    showSelected();
                    action.accept(repository);
                });
            } catch (EngineException e) {
                ui.execute(() -> {
                    setBusy(false);
                    if (e.kind() == EngineException.Kind.WRONG_PASSWORD) {
                        askAndCheck(job, location, msg.get("ask-password.wrong"), action);
                    } else {
                        view.showError(engineMessage(e));
                    }
                });
            }
        });
    }

    String engineMessage(EngineException e) {
        String key = "engine." + e.kind().key();
        return e.kind() == EngineException.Kind.FAILED || !msg.has(key) ? e.getMessage() : msg.get(key);
    }

    private void setBusy(boolean value) {
        busy = value;
        view.setBusy(value);
    }

    public boolean busy() {
        return busy;
    }

    public void setLanguage(Optional<Language> language) {
        try {
            locales.setApplicationLanguage(language);
        } catch (IOException e) {
            LOG.warn("Could not save the language", e);
            view.showError(msg.format("error.save-language", e.getMessage()));
            return;
        }
        if (locales.restartRequired()) {
            view.showInfo(Messages.commons(msg.locale()).format("language.restart", "amade-backup"));
        }
    }

    public void about() {
        view.showAbout(engineVersion);
    }

    public String version() {
        return AmadeBackupApplication.version();
    }

    public void exit() {
        if (busy && !view.confirm(msg.get("exit.title"), msg.get("exit.busy"))) return;
        view.close();
        exit.run();
    }
}

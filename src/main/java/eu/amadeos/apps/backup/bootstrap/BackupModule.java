package eu.amadeos.apps.backup.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.restic.ResticEngine;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.Readme;
import eu.amadeos.apps.backup.run.Restorer;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.sync.SyncExecutor;
import eu.amadeos.apps.backup.sync.SyncPlanner;
import eu.amadeos.apps.backup.sync.Trash;
import eu.amadeos.apps.backup.ui.main.MainWindow;
import eu.amadeos.apps.backup.ui.main.MainView;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.PasswordStore;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/**
 * amade-backup bindings. Theme, language, keyring and the other shared services come from
 * {@link eu.amadeos.commons.app.CommonsModule}.
 */
public final class BackupModule extends AbstractModule {
    /** The texts, {@code messages.properties} and its translations. */
    public static final String MESSAGES = "eu.amadeos.apps.backup.messages";

    @Override
    protected void configure() {
        bind(MainWindow.class).in(Scopes.SINGLETON);
        bind(MainView.class).to(MainWindow.class);
        bind(CommandRunner.class).toInstance(CommandRunner.system());
        bind(Clock.class).toInstance(Clock.systemDefaultZone());
        bind(Disks.class).toInstance(Disks.system());
    }

    /** The texts in the language {@code AmadeApplication} chose. */
    @Provides
    @Singleton
    Messages messages(Locale locale) {
        return Messages.load(BackupModule.class, MESSAGES, locale);
    }

    /** {@code $XDG_STATE_HOME/amadeos/backup}: history and logs, which are not settings and not a cache. */
    @Provides
    @Singleton
    @Named("state")
    Path state(XdgDirectories xdg) {
        return stateHome(System.getenv(), xdg.home()).resolve("amadeos/backup");
    }

    static Path stateHome(Map<String, String> env, Path home) {
        String state = env.get("XDG_STATE_HOME");
        return state != null && state.startsWith("/") ? Path.of(state) : home.resolve(".local/state");
    }

    @Provides
    @Singleton
    JobStore jobs(AppDirectories directories) {
        return new JobStore(directories.configFile(JobStore.FILE));
    }

    @Provides
    @Singleton
    HistoryStore history(@Named("state") Path state) {
        return new HistoryStore(state);
    }

    @Provides
    @Singleton
    BackupEngine engine(CommandRunner runner) {
        return new ResticEngine(runner);
    }

    @Provides
    @Singleton
    Trash trash(XdgDirectories xdg) {
        return Trash.forUser(xdg.home(), xdg.dataHome());
    }

    @Provides
    @Singleton
    Places places(Disks disks) {
        return new Places(disks);
    }

    @Provides
    @Singleton
    Passwords passwords(PasswordStore store) {
        return new Passwords(store);
    }

    @Provides
    @Singleton
    BackupRunner backups(BackupEngine engine, Places places, Passwords passwords, HistoryStore history,
                         CommandRunner runner, XdgDirectories xdg, Messages msg, Clock clock) {
        Readme readme = new Readme(msg, Messages.load(BackupModule.class, MESSAGES, Locale.ENGLISH));
        return new BackupRunner(engine, places, passwords, history, runner, xdg.home(), xdg.dataHome(), readme, clock);
    }

    @Provides
    @Singleton
    SyncRunner syncs(Trash trash, HistoryStore history, CommandRunner runner, XdgDirectories xdg, Clock clock) {
        return new SyncRunner(new SyncPlanner(trash), new SyncExecutor(trash), history, runner, xdg.home(), clock);
    }

    @Provides
    @Singleton
    Restorer restorer(BackupEngine engine, Trash trash, XdgDirectories xdg) {
        return new Restorer(engine, trash, xdg.cacheHome());
    }
}

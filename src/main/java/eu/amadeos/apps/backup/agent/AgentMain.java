package eu.amadeos.apps.backup.agent;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.JobLock;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.notice.NoticeWindows;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code amade-backup --agent}: the helper without a window. It ends at once with {@value #ALREADY_RUNNING}
 * when another helper of the user runs, and with 0 as soon as no job runs by itself.
 */
public final class AgentMain {
    public static final int ALREADY_RUNNING = 3;
    /** The first look waits until the session has settled and the disks are mounted (seconds). */
    static final long FIRST_TICK_SECONDS = 20;
    static final long TICK_SECONDS = 30;
    private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);
    /** Held while the helper runs; a lock nobody refers to could be closed by the garbage collector. */
    private static JobLock held;

    private AgentMain() {
    }

    // --8<-- [start:start]
    public static void start(Injector injector) {
        Path state = injector.getInstance(Key.get(Path.class, Names.named("state")));
        Optional<JobLock> lock;
        try {
            lock = JobLock.tryAcquire(AgentLauncher.lockDirectory(state));
        } catch (IOException e) {
            LOG.error("Cannot lock {}", state, e);
            lock = Optional.empty();
        }
        if (lock.isEmpty()) {
            LOG.info("Another backup helper runs already");
            System.exit(ALREADY_RUNNING);
            return;
        }
        held = lock.get();
        Messages msg = injector.getInstance(Messages.class);
        Clock clock = injector.getInstance(Clock.class);
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "backup-agent"));
        NoticeWindows notices = new NoticeWindows(msg, new Formats(msg, clock.getZone()), AgentMain::openWindow);
        BackupAgent agent = new BackupAgent(injector.getInstance(JobStore.class), injector.getInstance(HistoryStore.class),
                injector.getInstance(Places.class), injector.getInstance(BackupRunner.class),
                injector.getInstance(SyncRunner.class), new AgentState(state.resolve(AgentState.FILE)), Power.system(),
                notices, msg, clock, thread);
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop, "backup-agent-stop"));
        LOG.info("Backup helper started");
        thread.scheduleWithFixedDelay(() -> {
            try {
                if (!agent.tick()) {
                    LOG.info("No job runs by itself; the backup helper ends");
                    System.exit(0);
                }
            } catch (RuntimeException e) {
                LOG.error("Backup helper failed", e);
            }
        }, FIRST_TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }
    // --8<-- [end:start]

    /** Opens the Backup &amp; Sync window from a notice. */
    private static void openWindow() {
        try {
            new ProcessBuilder("amade-backup").redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
        } catch (IOException e) {
            LOG.warn("Cannot open Backup & Sync: {}", e.getMessage());
        }
    }
}

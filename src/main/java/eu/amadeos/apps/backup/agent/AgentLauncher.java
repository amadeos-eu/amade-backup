package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.run.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The window's side of the helper: whether it runs, and starting it when a job runs by itself and the helper
 * is not there yet (the session starts it only while there is such a job). The helper holds a lock on
 * {@code <state>/agent/running.lock} as long as it runs. Settings &gt; Startup can switch it off
 * ({@value #SCRIPT} in {@code ~/.config/amadeos/startup.conf}); then the window does not start it either.
 */
public class AgentLauncher {
    /** The session's script in {@code /etc/amadeos/autostart.d}. */
    public static final String SCRIPT = "60-amade-backup";
    private static final Logger LOG = LoggerFactory.getLogger(AgentLauncher.class);

    /** Starts the helper process. */
    @FunctionalInterface
    public interface Starter {
        void start() throws IOException;
    }

    private final Path lockDirectory;
    private final Path startupConf;
    private final Starter starter;

    public AgentLauncher(Path lockDirectory, Path startupConf, Starter starter) {
        this.lockDirectory = lockDirectory;
        this.startupConf = startupConf;
        this.starter = starter;
    }

    // --8<-- [start:process]
    /**
     * {@code amade-backup --agent} in a session of its own, so it outlives the window, at low CPU and disk
     * priority like the session's script starts it; its output goes to {@code log}.
     */
    public static Starter process(Path log) {
        return () -> {
            Files.createDirectories(log.getParent());
            new ProcessBuilder("setsid", "nice", "-n", "10", "ionice", "-c3", "amade-backup", "--agent")
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectErrorStream(true)
                    .start();
        };
    }
    // --8<-- [end:process]

    public static Path lockDirectory(Path state) {
        return state.resolve("agent");
    }

    /** Whether a helper holds the lock just now. */
    public boolean running() {
        try {
            Optional<JobLock> lock = JobLock.tryAcquire(lockDirectory);
            if (lock.isEmpty()) return true;
            lock.get().close();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the helper is switched off in Settings &gt; Startup. */
    public boolean switchedOff() {
        if (!Files.isRegularFile(startupConf)) return false;
        try {
            for (String line : Files.readAllLines(startupConf, StandardCharsets.UTF_8)) {
                if (line.startsWith("disabled=")) {
                    return Arrays.stream(line.substring("disabled=".length()).split(","))
                            .map(String::strip).anyMatch(SCRIPT::equals);
                }
            }
        } catch (IOException e) {
            LOG.debug("Cannot read {}", startupConf, e);
        }
        return false;
    }

    /** Starts the helper if one of the jobs runs by itself and no helper runs; false if it could not be started. */
    public boolean ensureRunning(List<Job> jobs) {
        if (BackupAgent.automatic(jobs).isEmpty() || switchedOff() || running()) return true;
        try {
            starter.start();
            LOG.info("Started the backup helper");
            return true;
        } catch (IOException e) {
            LOG.warn("Cannot start the backup helper: {}", e.getMessage());
            return false;
        }
    }
}

package eu.amadeos.apps.backup.engine;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.FoundFile;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SnapshotEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps versioned backups in a repository. The application only talks to this interface; restic does the work
 * today ({@link eu.amadeos.apps.backup.engine.restic.ResticEngine}), an engine of our own could replace it.
 * Every method blocks and must run off the event dispatch thread.
 */
public interface BackupEngine {
    /** The engine's name and version, e.g. "restic 0.18.0". */
    String version() throws EngineException;

    /** True if {@code location} holds a repository of this engine (without opening it). */
    boolean isRepository(Path location);

    /** Creates an empty repository, encrypted with the repository's password. */
    void init(Repository repository) throws EngineException;

    /** Saves a new backup; {@link EngineException.Kind#CANCELLED} if stopped. */
    BackupSummary backup(Repository repository, BackupRequest request, Consumer<BackupProgress> progress,
                         Cancellation cancellation) throws EngineException;

    /** All backups, oldest first. */
    List<Snapshot> snapshots(Repository repository) throws EngineException;

    /** The entries directly inside {@code directory} (an absolute path) of a backup. */
    List<SnapshotEntry> list(Repository repository, String snapshotId, String directory) throws EngineException;

    /** Files and folders whose name matches {@code pattern} ({@code *} and {@code ?}), in all backups. */
    List<FoundFile> find(Repository repository, String pattern) throws EngineException;

    /**
     * Writes {@code paths} of a backup below {@code target}, each under its full original path
     * ({@code target/home/ralf/...}).
     */
    void restore(Repository repository, String snapshotId, List<String> paths, Path target,
                 Consumer<BackupProgress> progress, Cancellation cancellation) throws EngineException;

    /** Writes one file of a backup to {@code file}. */
    void dump(Repository repository, String snapshotId, String path, Path file) throws EngineException;

    /** What changed between two backups. */
    DiffStats diff(Repository repository, String fromSnapshot, String toSnapshot) throws EngineException;

    /** Removes the backups the policy does not keep and frees their space; returns how many went. */
    int forget(Repository repository, KeepPolicy keep, Cancellation cancellation) throws EngineException;

    /** Checks the repository and reads {@code readPercent} percent of the data; the problems, empty if none. */
    List<String> check(Repository repository, int readPercent, Cancellation cancellation) throws EngineException;
}

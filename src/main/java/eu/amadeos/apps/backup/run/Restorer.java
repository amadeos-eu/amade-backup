package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.sync.Trash;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Puts files from a backup back. restic writes them into a hidden staging folder first; from there each file
 * is moved to its place, and a file that is already there is never overwritten silently: the
 * {@link Conflicts} policy replaces it (the old one goes into the trash), keeps both or skips it.
 */
public class Restorer {
    public enum Conflicts {
        REPLACE, KEEP_BOTH, SKIP;

        public String key() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /** How many files were put back and how many were left because they existed. */
    public record Result(int restored, int skipped, List<String> errors) {
        public Result {
            errors = List.copyOf(errors);
        }
    }

    private final BackupEngine engine;
    private final Trash trash;
    private final Path cacheHome;

    public Restorer(BackupEngine engine, Trash trash, Path cacheHome) {
        this.engine = engine;
        this.trash = trash;
        this.cacheHome = cacheHome;
    }

    /** The places the entries would be restored to that exist already. */
    public static List<Path> existing(List<SnapshotEntry> entries, Path destination) {
        List<Path> found = new ArrayList<>();
        for (SnapshotEntry entry : entries) {
            Path to = place(entry, destination);
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) found.add(to);
        }
        return found;
    }

    /** Where an entry goes: its original path, or into {@code destination} if that is not null. */
    public static Path place(SnapshotEntry entry, Path destination) {
        Path original = Path.of(entry.path());
        return destination == null ? original : destination.resolve(original.getFileName().toString());
    }

    public Result restore(Repository repository, String snapshotId, List<SnapshotEntry> entries, Path destination,
                          Conflicts conflicts, Consumer<BackupProgress> progress, Cancellation cancellation)
            throws EngineException {
        Path staging = staging(entries, destination);
        try {
            engine.restore(repository, snapshotId, entries.stream().map(SnapshotEntry::path).toList(), staging, progress,
                    cancellation);
            int[] counts = {0, 0};
            List<String> errors = new ArrayList<>();
            for (SnapshotEntry entry : entries) {
                Path staged = staging.resolve(Path.of("/").relativize(Path.of(entry.path())).toString());
                if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    errors.add(entry.path() + ": not in the backup");
                    continue;
                }
                move(staged, place(entry, destination), conflicts, counts, errors);
            }
            return new Result(counts[0], counts[1], errors);
        } finally {
            deleteTree(staging);
        }
    }

    /** A folder on the same disk as the destination, so files are moved and not copied a second time. */
    Path staging(List<SnapshotEntry> entries, Path destination) throws EngineException {
        Path dest = destination != null ? destination : Path.of(entries.getFirst().path()).getParent();
        String name = ".amade-restore-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
        try {
            Path parent = dest;
            while (parent != null && !Files.isDirectory(parent)) parent = parent.getParent();
            Path staging = parent == null || !Files.isWritable(parent) ? cacheHome.resolve("amadeos/backup").resolve(name)
                    : parent.resolve(name);
            Files.createDirectories(staging);
            return staging;
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, "Cannot create a folder to restore into: "
                    + e.getMessage(), e);
        }
    }

    private void move(Path staged, Path to, Conflicts conflicts, int[] counts, List<String> errors) {
        try {
            boolean exists = Files.exists(to, LinkOption.NOFOLLOW_LINKS);
            if (exists && Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                // Folders are merged: each file inside meets the policy on its own.
                try (DirectoryStream<Path> children = Files.newDirectoryStream(staged)) {
                    for (Path child : children) move(child, to.resolve(child.getFileName().toString()), conflicts, counts, errors);
                }
                return;
            }
            if (exists) {
                switch (conflicts) {
                    case SKIP -> {
                        counts[1]++;
                        return;
                    }
                    case REPLACE -> trash.moveToTrash(to);
                    case KEEP_BOTH -> to = freeName(to);
                }
            }
            Files.createDirectories(to.getParent());
            try {
                Files.move(staged, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, to);
            }
            counts[0]++;
        } catch (IOException e) {
            errors.add(to + ": " + e.getMessage());
        }
    }

    /** "report.pdf" to "report (restored).pdf", then "report (restored 2).pdf". */
    static Path freeName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            Path candidate = path.resolveSibling(base + (n == 1 ? " (restored)" : " (restored " + n + ")") + ext);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            // left for the next start
        }
    }
}

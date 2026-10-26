package eu.amadeos.apps.backup.sync;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Carries out a {@link SyncPlan}. A copy is written under a hidden name next to its place and renamed when it
 * is complete, so a cancelled sync, a full stick or a pulled plug never leave a half file under the real name.
 * Removed and replaced files go into the target's trash. Times are kept, permissions where the target's file
 * system has them. A failing file is reported and the sync goes on with the next one.
 */
public final class SyncExecutor {
    private static final int BUFFER = 1 << 20;

    private final Trash trash;

    public SyncExecutor(Trash trash) {
        this.trash = trash;
    }

    public SyncResult apply(SyncPlan plan, Consumer<BackupProgress> progress, Cancellation cancellation)
            throws EngineException {
        Path source = plan.pair().source().toAbsolutePath().normalize();
        Path target = plan.pair().target().toAbsolutePath().normalize();
        long total = plan.bytesToCopy();
        long totalSteps = plan.actions().stream().filter(a -> a.changes()).count();
        long[] done = {0, 0};
        long copied = 0, updated = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, "Cannot create " + target + ": " + e.getMessage(), e);
        }
        boolean permissions = !plan.coarseTimes();
        for (SyncAction action : plan.actions()) {
            if (!action.changes()) continue;
            cancellation.throwIfCancelled();
            Path from = source.resolve(action.path());
            Path to = target.resolve(action.path());
            progress.accept(new BackupProgress(total == 0 ? (double) done[1] / Math.max(1, totalSteps)
                    : (double) done[0] / total, done[1], totalSteps, done[0], total, List.of(action.path())));
            try {
                switch (action.kind()) {
                    case MKDIR -> {
                        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                            trash.moveToTrash(to);
                        }
                        Files.createDirectories(to);
                        if (permissions) copyPermissions(from, to);
                    }
                    case COPY, UPDATE -> {
                        if (Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) trash.moveToTrash(to);
                        long before = done[0];
                        if (Files.isSymbolicLink(from)) {
                            copyLink(from, to);
                        } else {
                            copyFile(from, to, permissions, n -> {
                                done[0] += n;
                                progress.accept(new BackupProgress(total == 0 ? 0 : (double) done[0] / total, done[1],
                                        totalSteps, done[0], total, List.of(action.path())));
                            }, cancellation);
                        }
                        if (action.kind() == SyncAction.Kind.COPY) copied++; else updated++;
                        done[0] = before + action.size();
                    }
                    case DELETE -> {
                        trash.moveToTrash(to);
                        deleted++;
                    }
                    case SKIP -> { }
                }
            } catch (IOException e) {
                errors.add(action.path() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            done[1]++;
        }
        setFolderTimes(target, plan.folderTimes());
        progress.accept(new BackupProgress(1, done[1], totalSteps, done[0], total, List.of()));
        return new SyncResult(copied, updated, deleted, done[0], errors);
    }

    private static void copyFile(Path from, Path to, boolean permissions, Consumer<Long> written,
                                 Cancellation cancellation) throws IOException, EngineException {
        Files.createDirectories(to.getParent());
        Path part = to.resolveSibling("." + to.getFileName() + SyncPlanner.PART);
        try {
            try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    cancellation.throwIfCancelled();
                    out.write(buffer, 0, n);
                    written.accept((long) n);
                }
            }
            Files.setLastModifiedTime(part, Files.getLastModifiedTime(from));
            if (permissions) copyPermissions(from, part);
            try {
                Files.move(part, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | EngineException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private void copyLink(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) trash.moveToTrash(to);
        Files.createSymbolicLink(to, Files.readSymbolicLink(from));
    }

    private static void copyPermissions(Path from, Path to) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributeView view = Files.getFileAttributeView(to, PosixFileAttributeView.class);
            if (view != null) view.setPermissions(perms);
        } catch (IOException | UnsupportedOperationException e) {
            // the target keeps its defaults
        }
    }

    /** Deepest folders first, because setting a time inside a folder would change the folder's own. */
    private static void setFolderTimes(Path target, Map<String, FileTime> times) {
        times.entrySet().stream().sorted(Comparator.comparing((Map.Entry<String, FileTime> e) -> e.getKey().length())
                .reversed()).forEach(e -> {
            Path dir = e.getKey().isEmpty() ? target : target.resolve(e.getKey());
            try {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Files.setLastModifiedTime(dir, e.getValue());
            } catch (IOException ex) {
                // not worth failing the sync for
            }
        });
    }
}

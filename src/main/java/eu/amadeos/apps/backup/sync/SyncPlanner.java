package eu.amadeos.apps.backup.sync;

import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what a sync would do, the way EuroSync's engine did: a file is copied when the target lacks it and
 * updated when size or time differ (times within 2 seconds count as equal on FAT and exFAT); with mirroring,
 * what is gone at the source is removed from the target. Links are copied as links, never followed; left-out
 * entries are neither copied nor removed on the target.
 */
public final class SyncPlanner {
    /** The ending of the hidden names copies are written under until they are complete. */
    public static final String PART = ".amade-part";

    private final Trash trash;

    public SyncPlanner(Trash trash) {
        this.trash = trash;
    }

    public SyncPlan plan(FolderPair pair, SyncOptions options, Patterns excludes, Cancellation cancellation)
            throws IOException, EngineException {
        Path source = pair.source().toAbsolutePath().normalize();
        Path target = pair.target().toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) throw new NoSuchFileException(source.toString(), null, "Source folder missing");
        if (target.startsWith(source) || source.startsWith(target)) {
            throw new IOException("Source and target must not lie inside each other: " + source + ", " + target);
        }
        // --8<-- [start:tolerance]
        boolean coarse = FileSystemInfo.isWindowsType(FileSystemInfo.type(target));
        long tolerance = coarse ? 2000 : 0;
        // --8<-- [end:tolerance]
        List<SyncAction> actions = new ArrayList<>();
        Map<String, FileTime> folderTimes = new HashMap<>();
        copyPass(source, target, Path.of(""), options, excludes, tolerance, actions, folderTimes, cancellation);
        if (options.mirror() && Files.isDirectory(target)) {
            deletePass(source, target, Path.of(""), excludes, actions, cancellation);
        }
        return new SyncPlan(pair, actions, folderTimes, FileSystemInfo.usableSpace(target), coarse);
    }

    private void copyPass(Path sourceRoot, Path targetRoot, Path relative, SyncOptions options, Patterns excludes,
                          long tolerance, List<SyncAction> actions, Map<String, FileTime> folderTimes,
                          Cancellation cancellation) throws IOException, EngineException {
        Path dir = sourceRoot.resolve(relative);
        folderTimes.put(slash(relative), Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS));
        for (Path child : sorted(dir)) {
            cancellation.throwIfCancelled();
            Path rel = relative.resolve(child.getFileName());
            String path = slash(rel);
            if (excludes.excludes(child, rel)) {
                actions.add(new SyncAction(SyncAction.Kind.SKIP, path, Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS),
                        0, SyncAction.Reason.EXCLUDED));
                continue;
            }
            BasicFileAttributes src = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path dest = targetRoot.resolve(rel);
            BasicFileAttributes dst = attributes(dest);
            if (src.isDirectory()) {
                if (dst == null) {
                    actions.add(new SyncAction(SyncAction.Kind.MKDIR, path, true, 0, SyncAction.Reason.NEW));
                } else if (!dst.isDirectory()) {
                    actions.add(new SyncAction(SyncAction.Kind.MKDIR, path, true, 0, SyncAction.Reason.REPLACES_FILE));
                }
                copyPass(sourceRoot, targetRoot, rel, options, excludes, tolerance, actions, folderTimes, cancellation);
            } else if (src.isSymbolicLink()) {
                if (dst == null) {
                    actions.add(new SyncAction(SyncAction.Kind.COPY, path, false, 0, SyncAction.Reason.NEW));
                } else if (!dst.isSymbolicLink() || !Files.readSymbolicLink(child).equals(Files.readSymbolicLink(dest))) {
                    if (!options.ignoreExisting()) {
                        actions.add(new SyncAction(SyncAction.Kind.UPDATE, path, false, 0, dst.isDirectory()
                                ? SyncAction.Reason.REPLACES_FOLDER : SyncAction.Reason.SIZE));
                    }
                }
            } else if (src.isRegularFile()) {
                file(path, src, dst, options, tolerance, actions);
            }
            // sockets, pipes and devices are not copied
        }
    }

    // --8<-- [start:file]
    private static void file(String path, BasicFileAttributes src, BasicFileAttributes dst, SyncOptions options,
                             long tolerance, List<SyncAction> actions) {
        if (dst == null) {
            actions.add(new SyncAction(SyncAction.Kind.COPY, path, false, src.size(), SyncAction.Reason.NEW));
            return;
        }
        if (options.ignoreExisting()) return;
        if (dst.isDirectory()) {
            actions.add(new SyncAction(SyncAction.Kind.UPDATE, path, false, src.size(), SyncAction.Reason.REPLACES_FOLDER));
            return;
        }
        if (src.size() != dst.size()) {
            actions.add(new SyncAction(SyncAction.Kind.UPDATE, path, false, src.size(), SyncAction.Reason.SIZE));
            return;
        }
        if (options.sizeOnly()) return;
        long s = src.lastModifiedTime().toMillis();
        long d = dst.lastModifiedTime().toMillis();
        if (Math.abs(s - d) <= tolerance) return;
        if (options.skipNewer() && d > s) {
            actions.add(new SyncAction(SyncAction.Kind.SKIP, path, false, 0, SyncAction.Reason.TARGET_NEWER));
            return;
        }
        actions.add(new SyncAction(SyncAction.Kind.UPDATE, path, false, src.size(), SyncAction.Reason.TIME));
    }
    // --8<-- [end:file]

    private void deletePass(Path sourceRoot, Path targetRoot, Path relative, Patterns excludes, List<SyncAction> actions,
                            Cancellation cancellation) throws IOException, EngineException {
        for (Path child : sorted(targetRoot.resolve(relative))) {
            cancellation.throwIfCancelled();
            Path rel = relative.resolve(child.getFileName());
            String name = child.getFileName().toString();
            if (name.endsWith(PART) || trash.isTrash(child)) continue;
            if (excludes.excludes(sourceRoot.resolve(rel), rel)) continue;
            BasicFileAttributes dst = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes src = attributes(sourceRoot.resolve(rel));
            if (src == null) {
                actions.add(new SyncAction(SyncAction.Kind.DELETE, slash(rel), dst.isDirectory(),
                        dst.isDirectory() ? size(child) : dst.size(), SyncAction.Reason.GONE));
            } else if (src.isDirectory() && dst.isDirectory()) {
                deletePass(sourceRoot, targetRoot, rel, excludes, actions, cancellation);
            }
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Path> sorted(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(children::add);
        } catch (NotDirectoryException | NoSuchFileException e) {
            return children;
        }
        children.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return children;
    }

    private static long size(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static String slash(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}

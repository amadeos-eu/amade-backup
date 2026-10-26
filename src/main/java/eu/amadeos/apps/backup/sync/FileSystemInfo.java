package eu.amadeos.apps.backup.sync;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** What a sync needs to know about the target's file system. */
public final class FileSystemInfo {
    /** File systems that keep times in 2-second steps and have no Unix permissions. */
    static final Set<String> WINDOWS_TYPES = Set.of("vfat", "msdos", "fat", "exfat", "fuseblk", "ntfs", "ntfs3");

    private FileSystemInfo() {
    }

    /** The nearest existing folder at or above {@code path}. */
    static Path existing(Path path) {
        Path p = path.toAbsolutePath();
        while (p != null && !Files.exists(p)) p = p.getParent();
        return p == null ? Path.of("/") : p;
    }

    public static String type(Path path) {
        try {
            return Files.getFileStore(existing(path)).type();
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean isWindowsType(String type) {
        return WINDOWS_TYPES.contains(type.toLowerCase());
    }

    public static long usableSpace(Path path) {
        try {
            return Files.getFileStore(existing(path)).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** The folder the file system holding {@code path} is mounted at. */
    static Path mountRoot(Path path) throws IOException {
        Path p = existing(path).toRealPath();
        FileStore store = Files.getFileStore(p);
        while (p.getParent() != null && Files.getFileStore(p.getParent()).equals(store)) p = p.getParent();
        return p;
    }
}

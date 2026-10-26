package eu.amadeos.apps.backup.domain;

/**
 * Further limits of a backup.
 *
 * @param excludeCaches leave out folders that mark themselves as caches with a {@code CACHEDIR.TAG}
 * @param oneFileSystem stay on the file system of each source, so mounted sticks and network folders stay out
 * @param maxFileSize   leave out files larger than this many bytes, 0 for no limit
 */
public record BackupOptions(boolean excludeCaches, boolean oneFileSystem, long maxFileSize) {
    public static final BackupOptions DEFAULT = new BackupOptions(true, true, 0);

    public BackupOptions {
        if (maxFileSize < 0) throw new IllegalArgumentException("Negative size");
    }
}

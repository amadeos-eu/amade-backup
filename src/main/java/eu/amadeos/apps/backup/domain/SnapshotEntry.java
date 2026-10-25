package eu.amadeos.apps.backup.domain;

import java.time.Instant;

/**
 * A file or folder inside a backup.
 *
 * @param path the absolute path it had when it was backed up
 */
public record SnapshotEntry(String name, String path, Type type, long size, Instant modified) {
    public enum Type { DIRECTORY, FILE, LINK, OTHER }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }
}

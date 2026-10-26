package eu.amadeos.apps.backup.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** A file in a backup compared with the same path on the computer now. */
public enum Comparison {
    SAME, CHANGED, DELETED;

    public String key() {
        return name().toLowerCase();
    }

    /** Files count as changed when size or time differ; folders only as deleted or the same. */
    public static Comparison of(SnapshotEntry entry, Path now) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(now, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return DELETED;
        }
        if (entry.isDirectory()) return attrs.isDirectory() ? SAME : CHANGED;
        if (attrs.isDirectory()) return CHANGED;
        if (entry.type() == SnapshotEntry.Type.FILE && attrs.size() != entry.size()) return CHANGED;
        if (entry.modified() != null
                && Math.abs(attrs.lastModifiedTime().toMillis() - entry.modified().toEpochMilli()) > 1000) {
            return CHANGED;
        }
        return SAME;
    }
}

package eu.amadeos.apps.backup.sync;

import java.util.List;

/** What a sync did; {@code errors} lists what failed, "path: reason". */
public record SyncResult(long copied, long updated, long deleted, long bytes, List<String> errors) {
    public SyncResult {
        errors = List.copyOf(errors);
    }

    public SyncResult plus(SyncResult other) {
        List<String> all = new java.util.ArrayList<>(errors);
        all.addAll(other.errors);
        return new SyncResult(copied + other.copied, updated + other.updated, deleted + other.deleted,
                bytes + other.bytes, all);
    }

    public static SyncResult empty() {
        return new SyncResult(0, 0, 0, 0, List.of());
    }
}

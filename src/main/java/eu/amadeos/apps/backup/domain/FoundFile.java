package eu.amadeos.apps.backup.domain;

import java.time.Instant;

/** A file found in one of the backups by name. */
public record FoundFile(String snapshotId, String path, SnapshotEntry.Type type, long size, Instant modified) {
}

package eu.amadeos.apps.backup.domain;

import java.time.Duration;
import java.util.List;

/**
 * What a finished backup did.
 *
 * @param bytesAdded what it added to the repository, after deduplication and compression
 * @param errors     files that could not be read, "path: reason"
 */
public record BackupSummary(String snapshotId, long filesNew, long filesChanged, long filesUnmodified,
                            long totalFiles, long totalBytes, long bytesAdded, Duration took, List<String> errors) {
    public BackupSummary {
        errors = List.copyOf(errors);
    }
}

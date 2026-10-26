package eu.amadeos.apps.backup.domain;

import java.util.List;

/** How far a running backup or restore is; totals are 0 while they are still being counted. */
public record BackupProgress(double fraction, long filesDone, long totalFiles, long bytesDone, long totalBytes,
                             List<String> currentFiles) {
    public BackupProgress {
        fraction = Math.max(0, Math.min(1, fraction));
        currentFiles = currentFiles == null ? List.of() : List.copyOf(currentFiles);
    }
}

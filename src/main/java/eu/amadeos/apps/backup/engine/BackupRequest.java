package eu.amadeos.apps.backup.engine;

import eu.amadeos.apps.backup.domain.BackupOptions;

import java.nio.file.Path;
import java.util.List;

/** What one backup run saves. */
public record BackupRequest(List<Path> sources, List<String> excludes, BackupOptions options, List<String> tags) {
    public BackupRequest {
        sources = List.copyOf(sources);
        excludes = List.copyOf(excludes);
        tags = List.copyOf(tags);
        if (sources.isEmpty()) throw new IllegalArgumentException("Nothing to back up");
    }
}

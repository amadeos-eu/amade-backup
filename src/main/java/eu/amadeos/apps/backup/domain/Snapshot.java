package eu.amadeos.apps.backup.domain;

import java.time.Instant;
import java.util.List;

/** One backup in a repository. */
public record Snapshot(String id, String shortId, Instant time, List<String> paths, List<String> tags) {
    public Snapshot {
        paths = List.copyOf(paths);
        tags = List.copyOf(tags);
    }
}

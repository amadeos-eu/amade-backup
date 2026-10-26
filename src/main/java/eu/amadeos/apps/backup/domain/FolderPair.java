package eu.amadeos.apps.backup.domain;

import java.nio.file.Path;
import java.util.Objects;

/** A sync copies {@code source} into {@code target}. */
public record FolderPair(Path source, Path target) {
    public FolderPair {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}

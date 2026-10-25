package eu.amadeos.apps.backup.sync;

import eu.amadeos.apps.backup.domain.FolderPair;

import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

/**
 * Everything one folder pair's sync would do, worked out without changing anything: the preview, and the
 * plan the {@link SyncExecutor} carries out.
 *
 * @param folderTimes the times of the source's folders by relative path, to set after copying into them
 * @param usableSpace bytes free on the target's disk
 * @param coarseTimes the target keeps times only to 2 seconds (FAT, exFAT, NTFS through FUSE)
 */
public record SyncPlan(FolderPair pair, List<SyncAction> actions, Map<String, FileTime> folderTimes,
                       long usableSpace, boolean coarseTimes) {
    public SyncPlan {
        actions = List.copyOf(actions);
        folderTimes = Map.copyOf(folderTimes);
    }

    public long count(SyncAction.Kind kind) {
        return actions.stream().filter(a -> a.kind() == kind).count();
    }

    /** Bytes that will be written. */
    public long bytesToCopy() {
        return actions.stream().filter(a -> a.kind() == SyncAction.Kind.COPY || a.kind() == SyncAction.Kind.UPDATE)
                .mapToLong(SyncAction::size).sum();
    }

    public boolean changesAnything() {
        return actions.stream().anyMatch(SyncAction::changes);
    }

    public boolean deletesAnything() {
        return actions.stream().anyMatch(a -> a.kind() == SyncAction.Kind.DELETE
                || a.reason() == SyncAction.Reason.REPLACES_FILE || a.reason() == SyncAction.Reason.REPLACES_FOLDER);
    }

    /** True if the copies need more space than the target has; replaced files are not counted as freed. */
    public boolean tooLarge() {
        return bytesToCopy() > usableSpace;
    }
}

package eu.amadeos.apps.backup.domain;

/**
 * How a sync compares and what it may change; the options of EuroSync, without the ones that only rsync
 * knows. Times are always kept, permissions whenever the target's file system has them.
 *
 * @param mirror         true: the target becomes a copy, so files gone at the source are removed (into the
 *                       target's trash); false: only add and update
 * @param ignoreExisting a file that exists on the target is never replaced
 * @param sizeOnly       files of the same size count as the same, whatever their time
 * @param skipNewer      a file that is newer on the target is left alone
 * @param before         a shell command run before the sync, "" for none
 * @param stopIfBeforeFails the sync does not start if {@code before} fails
 * @param after          a shell command run after a sync that changed something, "" for none
 */
public record SyncOptions(boolean mirror, boolean ignoreExisting, boolean sizeOnly, boolean skipNewer, String before,
                          boolean stopIfBeforeFails, String after) {
    public static final SyncOptions DEFAULT = new SyncOptions(true, false, false, false, "", true, "");

    public SyncOptions {
        before = before == null ? "" : before.strip();
        after = after == null ? "" : after.strip();
    }
}

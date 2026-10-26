package eu.amadeos.apps.backup.sync;

/**
 * One step of a sync, as the preview shows it.
 *
 * @param path      relative to the folder pair, with {@code /}
 * @param directory true for a folder
 * @param size      bytes to copy, or for a removal the bytes that go to the trash
 */
public record SyncAction(Kind kind, String path, boolean directory, long size, Reason reason) {
    public enum Kind {
        /** A new folder. */
        MKDIR,
        /** A file or link that the target does not have. */
        COPY,
        /** A file that differs. */
        UPDATE,
        /** Gone at the source: into the target's trash. */
        DELETE,
        /** Left alone, with the reason. */
        SKIP;

        public String key() {
            return name().toLowerCase();
        }
    }

    public enum Reason {
        NEW, SIZE, TIME, REPLACES_FILE, REPLACES_FOLDER, GONE, EXCLUDED, TARGET_NEWER;

        public String key() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    public boolean changes() {
        return kind != Kind.SKIP;
    }
}

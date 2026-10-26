package eu.amadeos.apps.backup.domain;

/** How a run ended. */
public enum RunResult {
    /** Everything was saved. */
    OK,
    /** Saved, but some files could not be read. */
    INCOMPLETE,
    /** Nothing was saved, e.g. the disk was full or the password wrong. */
    FAILED,
    /** The disk was not there; the run is made up when it appears. */
    TARGET_MISSING,
    /** Stopped by the user. */
    CANCELLED;

    public String key() {
        return name().toLowerCase().replace('_', '-');
    }

    public boolean saved() {
        return this == OK || this == INCOMPLETE;
    }
}

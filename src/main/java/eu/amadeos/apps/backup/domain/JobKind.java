package eu.amadeos.apps.backup.domain;

/** A backup keeps older versions in an encrypted repository; a sync makes a folder a copy of another one. */
public enum JobKind {
    BACKUP, SYNC;

    public String key() {
        return name().toLowerCase();
    }
}

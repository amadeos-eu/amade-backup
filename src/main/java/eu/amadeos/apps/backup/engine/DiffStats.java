package eu.amadeos.apps.backup.engine;

/** Files added, changed and removed between two backups. */
public record DiffStats(long added, long changed, long removed) {
}

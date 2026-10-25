package eu.amadeos.apps.backup.disk;

import java.nio.file.Path;

/**
 * A mounted disk or stick that a job can write to.
 *
 * @param label  the file system's name, else the folder it is mounted at
 * @param uuid   the file system UUID, "" if it has none
 * @param type   the file system type, e.g. "ext4" or "exfat"
 * @param usable bytes free for the user
 */
public record Disk(String label, Path mountPoint, String uuid, String type, long usable, long total) {
}

package eu.amadeos.apps.backup.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a job writes. On a removable disk the place is the disk's file system UUID and a path inside it, so
 * the job finds the disk wherever it is mounted; otherwise an absolute path.
 *
 * @param diskUuid  the file system UUID, "" for a folder that is always there
 * @param diskLabel the disk's name when the job was made, for messages while it is absent
 * @param path      inside the disk (relative) or absolute
 */
public record Target(String diskUuid, String diskLabel, Path path) {
    public Target {
        diskUuid = diskUuid == null ? "" : diskUuid.strip();
        diskLabel = diskLabel == null ? "" : diskLabel.strip();
        Objects.requireNonNull(path, "path");
        if (diskUuid.isEmpty() && !path.isAbsolute()) throw new IllegalArgumentException("Folder needs an absolute path");
        if (!diskUuid.isEmpty() && path.isAbsolute()) throw new IllegalArgumentException("Path on a disk must be relative");
    }

    public static Target folder(Path absolute) {
        return new Target("", "", absolute.toAbsolutePath().normalize());
    }

    public static Target onDisk(String uuid, String label, Path relative) {
        return new Target(uuid, label, relative.normalize());
    }

    public boolean onRemovableDisk() {
        return !diskUuid.isEmpty();
    }

    /** The absolute place, with the disk mounted at {@code mountPoint}. */
    public Path resolve(Path mountPoint) {
        return onRemovableDisk() ? mountPoint.resolve(path).normalize() : path;
    }
}

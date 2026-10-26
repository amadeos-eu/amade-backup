package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.disk.Disk;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.Target;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Where a job's target is right now: on its disk if that is plugged in, or the folder if it exists. */
public class Places {
    /** The folder on a backup disk that holds one repository per job. */
    public static final String FOLDER = "amadeos-backup";

    private final Disks disks;

    public Places(Disks disks) {
        this.disks = disks;
    }

    public Disks disks() {
        return disks;
    }

    public Optional<Path> resolve(Target target) {
        if (target.onRemovableDisk()) {
            return disks.byUuid(target.diskUuid()).map(d -> target.resolve(d.mountPoint()));
        }
        // The folder, its parent, or for a new backup the folder that gets the amadeos-backup folder; a
        // missing network share or unmounted disk further up counts as not there, so nothing is created on
        // the wrong disk.
        Path parent = target.path().getParent();
        boolean there = Files.isDirectory(target.path()) || parent != null && (Files.isDirectory(parent)
                || parent.getFileName() != null && parent.getFileName().toString().equals(FOLDER)
                && parent.getParent() != null && Files.isDirectory(parent.getParent()));
        return there ? Optional.of(target.path()) : Optional.empty();
    }

    /** The target for a folder: on its disk when it lies on a plugged-in disk with a UUID. */
    public Target targetFor(Path folder) {
        Path abs = folder.toAbsolutePath().normalize();
        Optional<Disk> disk = disks.containing(abs);
        if (disk.isPresent() && !disk.get().uuid().isEmpty()) {
            return Target.onDisk(disk.get().uuid(), disk.get().label(), disk.get().mountPoint().relativize(abs));
        }
        return Target.folder(abs);
    }
}

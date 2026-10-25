package eu.amadeos.apps.backup.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetTest {
    @Test
    void aTargetOnADiskIsRelativeToWhereverTheDiskIsMounted() {
        Target t = Target.onDisk("1234-ABCD", "BACKUP", Path.of("amadeos-backup/home"));
        assertTrue(t.onRemovableDisk());
        assertEquals(Path.of("/media/ralf/BACKUP/amadeos-backup/home"), t.resolve(Path.of("/media/ralf/BACKUP")));
        assertEquals(Path.of("/run/media/ralf/BACKUP1/amadeos-backup/home"), t.resolve(Path.of("/run/media/ralf/BACKUP1")));
    }

    @Test
    void aFolderTargetIsAbsolute() {
        Target t = Target.folder(Path.of("/srv/backups/amadeos-backup"));
        assertFalse(t.onRemovableDisk());
        assertEquals(Path.of("/srv/backups/amadeos-backup"), t.path());
    }

    @Test
    void pathsMustFitTheKind() {
        assertThrows(IllegalArgumentException.class, () -> Target.onDisk("1234", "X", Path.of("/absolute")));
        assertThrows(IllegalArgumentException.class, () -> new Target("", "", Path.of("relative")));
    }
}

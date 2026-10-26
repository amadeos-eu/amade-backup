package eu.amadeos.apps.backup.disk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisksTest {
    @TempDir
    Path dir;

    @Test
    void escapesOfMountsAndUdevAreUndone() {
        assertEquals("/media/ralf/My Disk", Disks.unescape("/media/ralf/My\\040Disk"));
        assertEquals("My Disk", Disks.unescape("My\\x20Disk"));
        assertEquals("plain\\x", Disks.unescape("plain\\x"));
    }

    @Test
    void onlyRemovableMountsAreListedWithUuidAndLabel() throws Exception {
        Path dev = Files.createDirectories(dir.resolve("dev"));
        Path sdb1 = Files.createFile(dev.resolve("sdb1"));
        Path byUuid = Files.createDirectories(dir.resolve("by-uuid"));
        Path byLabel = Files.createDirectories(dir.resolve("by-label"));
        Files.createSymbolicLink(byUuid.resolve("1234-ABCD"), sdb1);
        Files.createSymbolicLink(byLabel.resolve("My\\x20Disk"), sdb1);
        Path mounts = dir.resolve("mounts");
        Files.writeString(mounts, String.join("\n",
                "/dev/nvme0n1p2 / ext4 rw,relatime 0 0",
                "proc /proc proc rw 0 0",
                sdb1 + " /media/ralf/My\\040Disk exfat rw,nosuid 0 0",
                "/dev/sdc1 /mnt/nas nfs rw 0 0", ""));
        Disks disks = new Disks(mounts, byUuid, byLabel);
        List<Disk> found = disks.mounted();
        assertEquals(2, found.size());
        Disk stick = found.getFirst();
        assertEquals("My Disk", stick.label());
        assertEquals("1234-ABCD", stick.uuid());
        assertEquals("exfat", stick.type());
        assertEquals(Path.of("/media/ralf/My Disk"), stick.mountPoint());
        assertEquals("nas", found.get(1).label());
        assertEquals("", found.get(1).uuid());
        assertEquals(stick, disks.byUuid("1234-abcd").orElseThrow());
        assertEquals(stick, disks.containing(Path.of("/media/ralf/My Disk/amadeos-backup")).orElseThrow());
        assertTrue(disks.containing(Path.of("/home/ralf")).isEmpty());
    }
}

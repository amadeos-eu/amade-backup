package eu.amadeos.apps.backup.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrashTest {
    @TempDir
    Path dir;

    @Test
    void pathsAreEncodedPartByPart() {
        assertEquals("/home/ralf/My%20Files/%C3%A4%2B1.txt", Trash.encode("/home/ralf/My Files/ä+1.txt"));
    }

    @Test
    void aSecondFileOfTheSameNameGetsANumber() throws Exception {
        Trash trash = new Trash(dir.resolve("trash"), dir, "1000");
        Path a = Files.createDirectories(dir.resolve("one")).resolve("x.txt");
        Path b = Files.createDirectories(dir.resolve("two")).resolve("x.txt");
        Files.writeString(a, "1");
        Files.writeString(b, "2");
        assertEquals(dir.resolve("trash/files/x.txt"), trash.moveToTrash(a));
        assertEquals(dir.resolve("trash/files/x.txt.2"), trash.moveToTrash(b));
        assertEquals("2", Files.readString(dir.resolve("trash/files/x.txt.2")));
        assertTrue(Files.exists(dir.resolve("trash/info/x.txt.2.trashinfo")));
        assertTrue(trash.isTrash(dir.resolve("trash/files/x.txt")));
        assertFalse(trash.isTrash(dir.resolve("one")));
    }
}

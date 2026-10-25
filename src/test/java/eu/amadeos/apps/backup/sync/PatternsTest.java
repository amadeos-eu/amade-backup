package eu.amadeos.apps.backup.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternsTest {
    private static final Path HOME = Path.of("/home/ralf");
    private static final Path ROOT = Path.of("/home/ralf/Music");

    private static boolean out(Patterns p, String relative) {
        return p.excludes(ROOT.resolve(relative), Path.of(relative));
    }

    @Test
    void aNameMatchesAnywhere() {
        Patterns p = new Patterns(List.of("*.iso", "node_modules"), HOME);
        assertTrue(out(p, "disk.iso"));
        assertTrue(out(p, "a/b/disk.iso"));
        assertTrue(out(p, "project/node_modules"));
        assertFalse(out(p, "disk.iso.txt"));
    }

    @Test
    void aPathMatchesInsideTheFolderPair() {
        Patterns p = new Patterns(List.of("Old/*", "**/build/"), HOME);
        assertTrue(out(p, "Old/song.mp3"));
        assertFalse(out(p, "New/Old/song.mp3"));
        assertTrue(out(p, "build"));
        assertTrue(out(p, "a/b/build"));
    }

    @Test
    void absoluteAndHomePathsMatchTheRealPlace() {
        Patterns p = new Patterns(List.of("~/Music/Podcasts", "/home/ralf/Music/tmp"), HOME);
        assertTrue(out(p, "Podcasts"));
        assertTrue(out(p, "tmp"));
        assertFalse(out(p, "Rock/Podcasts"));
    }

    @Test
    void blankLinesAndCommentsAreIgnored() {
        Patterns p = new Patterns(List.of("", "  ", "# a comment"), HOME);
        assertFalse(out(p, "# a comment"));
        assertFalse(out(p, "anything"));
    }
}

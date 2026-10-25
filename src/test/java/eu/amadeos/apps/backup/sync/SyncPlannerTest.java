package eu.amadeos.apps.backup.sync;

import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPlannerTest {
    static final FileTime T1 = FileTime.from(Instant.parse("2026-10-01T10:00:00Z"));
    static final FileTime T2 = FileTime.from(Instant.parse("2026-10-20T10:00:00Z"));

    @TempDir
    Path dir;
    Path source;
    Path target;
    SyncPlanner planner;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectories(dir.resolve("source"));
        target = Files.createDirectories(dir.resolve("target"));
        planner = new SyncPlanner(new Trash(dir.resolve("trash"), dir, "1000"));
    }

    static void write(Path p, String text, FileTime time) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        Files.setLastModifiedTime(p, time);
    }

    private SyncPlan plan(SyncOptions options, String... patterns) throws IOException, EngineException {
        return planner.plan(new FolderPair(source, target), options, new Patterns(List.of(patterns), dir),
                Cancellation.none());
    }

    static String describe(SyncPlan plan) {
        return plan.actions().stream().map(a -> a.kind() + " " + a.path() + " " + a.reason())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void newFoldersAndFilesAreCopied() throws Exception {
        write(source.resolve("a.txt"), "a", T1);
        write(source.resolve("sub/b.txt"), "bb", T1);
        SyncPlan plan = plan(SyncOptions.DEFAULT);
        assertEquals("COPY a.txt NEW\nMKDIR sub NEW\nCOPY sub/b.txt NEW", describe(plan));
        assertEquals(3, plan.bytesToCopy());
        assertTrue(plan.changesAnything());
    }

    @Test
    void filesDifferingInSizeOrTimeAreUpdated() throws Exception {
        write(source.resolve("same.txt"), "x", T1);
        write(target.resolve("same.txt"), "x", T1);
        write(source.resolve("size.txt"), "longer", T1);
        write(target.resolve("size.txt"), "short", T1);
        write(source.resolve("time.txt"), "x", T2);
        write(target.resolve("time.txt"), "x", T1);
        assertEquals("UPDATE size.txt SIZE\nUPDATE time.txt TIME", describe(plan(SyncOptions.DEFAULT)));
    }

    @Test
    void mirroringRemovesWhatIsGoneButNotWhatIsLeftOut() throws Exception {
        write(source.resolve("keep.txt"), "k", T1);
        write(target.resolve("keep.txt"), "k", T1);
        write(target.resolve("gone.txt"), "g", T1);
        write(target.resolve("old/inside.txt"), "o", T1);
        write(target.resolve("cache.tmp"), "c", T1);
        write(target.resolve(".x.amade-part"), "half", T1);
        SyncPlan plan = plan(SyncOptions.DEFAULT, "*.tmp");
        assertEquals("DELETE gone.txt GONE\nDELETE old GONE", describe(plan));
        assertTrue(plan.deletesAnything());
    }

    @Test
    void updatingNeverRemoves() throws Exception {
        write(target.resolve("gone.txt"), "g", T1);
        SyncOptions update = new SyncOptions(false, false, false, false, "", true, "");
        assertFalse(plan(update).changesAnything());
    }

    @Test
    void optionsLeaveExistingAndNewerFilesAlone() throws Exception {
        write(source.resolve("a.txt"), "new text", T2);
        write(target.resolve("a.txt"), "old", T1);
        write(source.resolve("b.txt"), "x", T1);
        write(target.resolve("b.txt"), "x", T2);
        assertEquals("", describe(plan(new SyncOptions(true, true, false, false, "", true, ""))));
        assertEquals("UPDATE a.txt SIZE\nSKIP b.txt TARGET_NEWER",
                describe(plan(new SyncOptions(true, false, false, true, "", true, ""))));
        assertEquals("UPDATE a.txt SIZE", describe(plan(new SyncOptions(true, false, true, false, "", true, ""))));
    }

    @Test
    void leftOutEntriesAreListed() throws Exception {
        write(source.resolve("disk.iso"), "iso", T1);
        write(source.resolve("node_modules/x.js"), "js", T1);
        assertEquals("SKIP disk.iso EXCLUDED\nSKIP node_modules EXCLUDED",
                describe(plan(SyncOptions.DEFAULT, "*.iso", "node_modules")));
    }

    @Test
    void aFolderReplacesAFileOfTheSameName() throws Exception {
        write(source.resolve("x/inner.txt"), "i", T1);
        write(target.resolve("x"), "a file", T1);
        assertEquals("MKDIR x REPLACES_FILE\nCOPY x/inner.txt NEW", describe(plan(SyncOptions.DEFAULT)));
    }

    @Test
    void foldersInsideEachOtherAreRefused() {
        assertThrows(IOException.class, () -> planner.plan(new FolderPair(source, source.resolve("copy")),
                SyncOptions.DEFAULT, Patterns.NONE, Cancellation.none()));
        assertThrows(IOException.class, () -> planner.plan(new FolderPair(dir.resolve("missing"), target),
                SyncOptions.DEFAULT, Patterns.NONE, Cancellation.none()));
    }

    @Test
    void aCancelledPlanStops() throws Exception {
        write(source.resolve("a.txt"), "a", T1);
        Cancellation c = new Cancellation();
        c.cancel();
        EngineException e = assertThrows(EngineException.class, () -> planner.plan(new FolderPair(source, target),
                SyncOptions.DEFAULT, Patterns.NONE, c));
        assertEquals(EngineException.Kind.CANCELLED, e.kind());
    }
}

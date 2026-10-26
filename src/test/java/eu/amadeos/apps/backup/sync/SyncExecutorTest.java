package eu.amadeos.apps.backup.sync;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.engine.Cancellation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static eu.amadeos.apps.backup.sync.SyncPlannerTest.T1;
import static eu.amadeos.apps.backup.sync.SyncPlannerTest.T2;
import static eu.amadeos.apps.backup.sync.SyncPlannerTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncExecutorTest {
    @TempDir
    Path dir;
    Path source;
    Path target;
    Trash trash;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectories(dir.resolve("source"));
        target = dir.resolve("target");
        trash = new Trash(dir.resolve("trash"), dir, "1000");
    }

    private SyncResult sync() throws Exception {
        SyncPlan plan = new SyncPlanner(trash).plan(new FolderPair(source, target), SyncOptions.DEFAULT, Patterns.NONE,
                Cancellation.none());
        List<BackupProgress> progress = new ArrayList<>();
        SyncResult result = new SyncExecutor(trash).apply(plan, progress::add, Cancellation.none());
        assertEquals(1.0, progress.getLast().fraction());
        return result;
    }

    @Test
    void theTargetBecomesACopyWithTimesAndPermissions() throws Exception {
        write(source.resolve("a.txt"), "hello", T1);
        write(source.resolve("sub/deep/b.sh"), "#!/bin/sh", T2);
        Files.setPosixFilePermissions(source.resolve("sub/deep/b.sh"), PosixFilePermissions.fromString("rwxr-x---"));
        Files.setLastModifiedTime(source.resolve("sub"), T1);
        Files.createSymbolicLink(source.resolve("link"), Path.of("a.txt"));
        SyncResult result = sync();
        assertEquals(List.of(), result.errors());
        assertEquals("hello", Files.readString(target.resolve("a.txt")));
        assertEquals(T1, Files.getLastModifiedTime(target.resolve("a.txt")));
        assertEquals(T2, Files.getLastModifiedTime(target.resolve("sub/deep/b.sh")));
        assertEquals(T1, Files.getLastModifiedTime(target.resolve("sub")));
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("sub/deep/b.sh"))));
        assertEquals(Path.of("a.txt"), Files.readSymbolicLink(target.resolve("link")));
        // a second run has nothing to do
        assertEquals(0, sync().copied() + sync().updated());
    }

    @Test
    void removedFilesGoToTheTrashWithTheirInfo() throws Exception {
        write(source.resolve("keep.txt"), "k", T1);
        write(target.resolve("keep.txt"), "k", T1);
        write(target.resolve("gone file.txt"), "g", T1);
        SyncResult result = sync();
        assertEquals(1, result.deleted());
        assertFalse(Files.exists(target.resolve("gone file.txt")));
        assertEquals("g", Files.readString(dir.resolve("trash/files/gone file.txt")));
        String info = Files.readString(dir.resolve("trash/info/gone file.txt.trashinfo"));
        assertTrue(info.contains("Path=" + target.toString().replace(" ", "%20") + "/gone%20file.txt"), info);
        assertTrue(info.contains("DeletionDate="));
    }

    @Test
    void noHalfFilesAreLeftBehind() throws Exception {
        write(source.resolve("a.txt"), "new", T2);
        write(target.resolve("a.txt"), "older text", T1);
        assertEquals(1, sync().updated());
        assertEquals("new", Files.readString(target.resolve("a.txt")));
        try (var files = Files.list(target)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(SyncPlanner.PART)));
        }
    }

    @Test
    void anUnreadableFileIsReportedAndTheRestIsCopied() throws Exception {
        write(source.resolve("a.txt"), "a", T1);
        write(source.resolve("b.txt"), "b", T1);
        SyncPlan plan = new SyncPlanner(trash).plan(new FolderPair(source, target), SyncOptions.DEFAULT, Patterns.NONE,
                Cancellation.none());
        Files.delete(source.resolve("a.txt"));
        SyncResult result = new SyncExecutor(trash).apply(plan, p -> { }, Cancellation.none());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().startsWith("a.txt: "));
        assertEquals("b", Files.readString(target.resolve("b.txt")));
    }
}

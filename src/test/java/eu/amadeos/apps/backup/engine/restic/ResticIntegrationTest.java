package eu.amadeos.apps.backup.engine.restic;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.FoundFile;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.engine.BackupRequest;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.DiffStats;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.commons.process.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine against the real restic (installed on the build machine, see the workflow). */
class ResticIntegrationTest {
    @TempDir
    Path dir;

    private final ResticEngine engine = new ResticEngine(CommandRunner.system());

    @Test
    void backUpBrowseFindRestoreAndThinOut() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home/ralf"));
        Files.createDirectories(home.resolve("Documents"));
        Files.writeString(home.resolve("Documents/letter.txt"), "Dear Ada");
        Files.writeString(home.resolve("notes.txt"), "first");
        Files.createDirectories(home.resolve(".cache"));
        Files.writeString(home.resolve(".cache/junk"), "junk");
        Repository repo = new Repository(dir.resolve("disk/amadeos-backup/home"), "correct horse");

        assertFalse(engine.isRepository(repo.location()));
        engine.init(repo);
        assertTrue(engine.isRepository(repo.location()));
        BackupRequest request = new BackupRequest(List.of(home), List.of(home.resolve(".cache").toString()),
                BackupOptions.DEFAULT, List.of("home"));
        BackupSummary first = engine.backup(repo, request, p -> { }, Cancellation.none());
        assertEquals(2, first.filesNew());

        Files.writeString(home.resolve("notes.txt"), "second version");
        Files.delete(home.resolve("Documents/letter.txt"));
        BackupSummary second = engine.backup(repo, request, p -> { }, Cancellation.none());
        assertEquals(1, second.filesChanged());

        List<Snapshot> snapshots = engine.snapshots(repo);
        assertEquals(2, snapshots.size());
        assertEquals(first.snapshotId(), snapshots.getFirst().id());
        assertEquals(List.of("home"), snapshots.getFirst().tags());

        List<SnapshotEntry> top = engine.list(repo, first.snapshotId(), home.toString());
        assertEquals(List.of("Documents", "notes.txt"), top.stream().map(SnapshotEntry::name).toList());
        assertEquals(home.resolve("notes.txt").toString(), top.get(1).path());

        DiffStats diff = engine.diff(repo, first.snapshotId(), second.snapshotId());
        assertEquals(1, diff.changed());
        assertEquals(1, diff.removed());

        List<FoundFile> found = engine.find(repo, "letter.txt");
        assertEquals(1, found.size());
        assertEquals(home.resolve("Documents/letter.txt").toString(), found.getFirst().path());

        Path staging = Files.createDirectories(dir.resolve("staging"));
        engine.restore(repo, first.snapshotId(), List.of(home.resolve("Documents/letter.txt").toString()), staging,
                p -> { }, Cancellation.none());
        Path restored = staging.resolve(Path.of("/").relativize(home.resolve("Documents/letter.txt")).toString());
        assertEquals("Dear Ada", Files.readString(restored));
        assertFalse(Files.exists(staging.resolve(Path.of("/").relativize(home.resolve("notes.txt")).toString())));

        Path dumped = dir.resolve("old-notes.txt");
        engine.dump(repo, first.snapshotId(), home.resolve("notes.txt").toString(), dumped);
        assertEquals("first", Files.readString(dumped));

        assertEquals(1, engine.forget(repo, new KeepPolicy(0, 0, 0, 0, 0), Cancellation.none()));
        assertEquals(List.of(second.snapshotId()), engine.snapshots(repo).stream().map(Snapshot::id).toList());
        assertEquals(List.of(), engine.check(repo, 10, Cancellation.none()));
        assertTrue(engine.version().startsWith("restic "));
    }

    @Test
    void aWrongPasswordAndAMissingRepositoryAreRecognised() throws Exception {
        Repository repo = new Repository(dir.resolve("repo"), "right password");
        engine.init(repo);
        EngineException wrong = assertThrows(EngineException.class,
                () -> engine.snapshots(new Repository(repo.location(), "wrong password")));
        assertEquals(EngineException.Kind.WRONG_PASSWORD, wrong.kind());
        EngineException missing = assertThrows(EngineException.class,
                () -> engine.snapshots(new Repository(dir.resolve("nothing"), "x")));
        assertEquals(EngineException.Kind.NO_REPOSITORY, missing.kind());
    }
}

package eu.amadeos.apps.backup.engine.restic;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.engine.BackupRequest;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.FinishedProcess;
import eu.amadeos.commons.process.ProgramMissingException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine against canned restic output, so these tests show what each command line looks like. */
class ResticEngineTest {
    static final Repository REPO = new Repository(Path.of("/media/ralf/BACKUP/amadeos-backup/home"), "secret");

    /** Answers each start with the next canned process and remembers the commands. */
    // --8<-- [start:fake]
    static final class FakeRunner implements CommandRunner {
        final Deque<Process> answers = new ArrayDeque<>();
        final List<List<String>> commands = new ArrayList<>();

        FakeRunner answer(String stdout, String stderr, int exit) {
            answers.add(new FinishedProcess(stdout, stderr, exit));
            return this;
        }

        @Override
        public Process start(List<String> command) {
            commands.add(command);
            return answers.removeFirst();
        }
    }
    // --8<-- [end:fake]

    @Test
    void theVersionLeavesOutTheBuildDetails() throws Exception {
        FakeRunner runner = new FakeRunner().answer("restic 0.16.4 compiled with go1.22.2 on linux/amd64\n", "", 0);
        assertEquals("restic 0.16.4", new ResticEngine(runner).version());
    }

    @Test
    void aMissingProgramIsItsOwnKindOfFailure() {
        CommandRunner missing = command -> {
            throw new ProgramMissingException(command.getFirst(), null);
        };
        assertEquals(EngineException.Kind.PROGRAM_MISSING,
                assertThrows(EngineException.class, () -> new ResticEngine(missing).version()).kind());
        assertEquals(EngineException.Kind.PROGRAM_MISSING, assertThrows(EngineException.class,
                () -> new ResticEngine(missing).snapshots(REPO)).kind());
    }

    // --8<-- [start:progress]
    @Test
    void aBackupReportsProgressAndItsSummary() throws Exception {
        String out = """
                {"message_type":"status","percent_done":0.5,"total_files":10,"files_done":5,"total_bytes":2000,"bytes_done":1000,"current_files":["/home/ralf/a.txt"]}
                {"message_type":"summary","files_new":3,"files_changed":1,"files_unmodified":6,"total_files_processed":10,"total_bytes_processed":2000,"data_added":1500,"total_duration":2.5,"snapshot_id":"4f2a9c1d00"}
                """;
        FakeRunner runner = new FakeRunner().answer(out, "", 0);
        List<BackupProgress> progress = new ArrayList<>();
        BackupSummary s = new ResticEngine(runner).backup(REPO, new BackupRequest(List.of(Path.of("/home/ralf")),
                List.of("/home/ralf/.cache"), new BackupOptions(true, true, 4_000_000_000L), List.of("home")),
                progress::add, Cancellation.none());
        assertEquals(List.of("restic", "--repo", REPO.location().toString(), "backup", "--json", "--exclude-caches",
                "--one-file-system", "--exclude-larger-than", "4000000000b", "--exclude", "/home/ralf/.cache", "--tag",
                "home", "/home/ralf"), runner.commands.getFirst());
        assertEquals(0.5, progress.getFirst().fraction());
        assertEquals(List.of("/home/ralf/a.txt"), progress.getFirst().currentFiles());
        assertEquals("4f2a9c1d00", s.snapshotId());
        assertEquals(3, s.filesNew());
        assertEquals(1500, s.bytesAdded());
        assertEquals(List.of(), s.errors());
    }
    // --8<-- [end:progress]

    @Test
    void unreadableFilesMakeTheBackupIncompleteNotFailed() throws Exception {
        String out = "{\"message_type\":\"summary\",\"snapshot_id\":\"abc\",\"total_files_processed\":2}\n";
        String err = "{\"message_type\":\"error\",\"error\":{\"message\":\"permission denied\"},\"during\":\"archival\","
                + "\"item\":\"/home/ralf/secret\"}\n";
        FakeRunner runner = new FakeRunner().answer(out, err, ResticEngine.INCOMPLETE);
        BackupSummary s = new ResticEngine(runner).backup(REPO, new BackupRequest(List.of(Path.of("/home/ralf")),
                List.of(), BackupOptions.DEFAULT, List.of()), p -> { }, Cancellation.none());
        assertEquals(List.of("/home/ralf/secret: permission denied"), s.errors());
    }

    @Test
    void failuresAreSortedByKind() {
        assertKind(EngineException.Kind.WRONG_PASSWORD, 12, "Fatal: wrong password or no key found");
        assertKind(EngineException.Kind.WRONG_PASSWORD, 1, "Fatal: wrong password or no key found");
        assertKind(EngineException.Kind.NO_REPOSITORY, 1, "Fatal: unable to open config file: stat /x/config: "
                + "no such file or directory\nIs there a repository at the following location?");
        assertKind(EngineException.Kind.NO_REPOSITORY, 10, "");
        assertKind(EngineException.Kind.LOCKED, 1, "unable to create lock in backend: repository is already locked");
        assertKind(EngineException.Kind.FAILED, 1, "Fatal: something else");
    }

    private static void assertKind(EngineException.Kind kind, int exit, String stderr) {
        FakeRunner runner = new FakeRunner().answer("", stderr, exit);
        EngineException e = assertThrows(EngineException.class, () -> new ResticEngine(runner).snapshots(REPO));
        assertEquals(kind, e.kind(), stderr);
    }

    @Test
    void snapshotsAreReadOldestFirst() throws Exception {
        String out = """
                [{"time":"2026-10-25T21:00:03.123456789+01:00","paths":["/home/ralf"],"hostname":"amadeos","tags":["home"],"id":"bbbb2222","short_id":"bbbb"},
                 {"time":"2026-10-24T21:00:00+01:00","paths":["/home/ralf"],"hostname":"amadeos","id":"aaaa1111","short_id":"aaaa"}]
                """;
        List<Snapshot> snapshots = new ResticEngine(new FakeRunner().answer(out, "", 0)).snapshots(REPO);
        assertEquals(List.of("aaaa1111", "bbbb2222"), snapshots.stream().map(Snapshot::id).toList());
        assertEquals(Instant.parse("2026-10-25T20:00:03.123456789Z"), snapshots.getLast().time());
        assertEquals(List.of("home"), snapshots.getLast().tags());
    }

    @Test
    void listingShowsOnlyTheFolderItselfFoldersFirst() throws Exception {
        String out = """
                {"time":"2026-10-25T21:00:00Z","paths":["/home/ralf"],"id":"x","short_id":"x","struct_type":"snapshot"}
                {"name":"ralf","type":"dir","path":"/home/ralf","mtime":"2026-10-25T20:00:00Z","struct_type":"node"}
                {"name":"zeta.txt","type":"file","path":"/home/ralf/zeta.txt","size":5,"mtime":"2026-10-25T20:00:00Z","struct_type":"node"}
                {"name":"Documents","type":"dir","path":"/home/ralf/Documents","mtime":"2026-10-25T20:00:00Z","struct_type":"node"}
                {"name":"deep.txt","type":"file","path":"/home/ralf/Documents/deep.txt","size":1,"mtime":"2026-10-25T20:00:00Z","struct_type":"node"}
                {"name":"link","type":"symlink","path":"/home/ralf/link","mtime":"2026-10-25T20:00:00Z","struct_type":"node"}
                """;
        List<SnapshotEntry> entries = new ResticEngine(new FakeRunner().answer(out, "", 0)).list(REPO, "x", "/home/ralf");
        assertEquals(List.of("Documents", "link", "zeta.txt"), entries.stream().map(SnapshotEntry::name).toList());
        assertTrue(entries.getFirst().isDirectory());
        assertEquals(SnapshotEntry.Type.LINK, entries.get(1).type());
        assertEquals(5, entries.get(2).size());
    }

    @Test
    void forgetPrunesOnlyWhenSomethingWasRemoved() throws Exception {
        FakeRunner nothing = new FakeRunner().answer("[{\"keep\":[{}],\"remove\":null}]", "", 0);
        assertEquals(0, new ResticEngine(nothing).forget(REPO, KeepPolicy.DEFAULT, Cancellation.none()));
        assertEquals(1, nothing.commands.size());
        assertTrue(nothing.commands.getFirst().containsAll(List.of("forget", "--group-by", "paths", "--keep-last", "1")));

        FakeRunner some = new FakeRunner().answer("[{\"keep\":[{}],\"remove\":[{},{}]}]", "", 0).answer("", "", 0);
        assertEquals(2, new ResticEngine(some).forget(REPO, KeepPolicy.DEFAULT, Cancellation.none()));
        assertEquals("prune", some.commands.get(1).getLast());
    }
}

package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.sync.SyncExecutor;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.sync.SyncPlanner;
import eu.amadeos.apps.backup.sync.Trash;
import eu.amadeos.commons.process.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncRunnerTest {
    @TempDir
    Path dir;
    Path music;
    Path stick;
    HistoryStore history;
    SyncRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        music = Files.createDirectories(dir.resolve("Music"));
        stick = Files.createDirectories(dir.resolve("stick"));
        Files.writeString(music.resolve("song.mp3"), "la la");
        Files.writeString(music.resolve("list.m3u"), "song.mp3");
        Trash trash = new Trash(dir.resolve("trash"), dir, "1000");
        history = new HistoryStore(dir.resolve("state"));
        runner = new SyncRunner(new SyncPlanner(trash), new SyncExecutor(trash), history, CommandRunner.system(), dir,
                Clock.systemUTC());
    }

    private Job job(SyncOptions options) {
        return Job.sync("music", "Music", List.of(new FolderPair(music, stick.resolve("Music"))), options)
                .withExcludes(List.of("*.m3u"));
    }

    @Test
    void aSyncRunsItsCommandsAndIsRecorded() throws Exception {
        Path marker = dir.resolve("after-ran");
        Job job = job(new SyncOptions(true, false, false, false, "true", true, "touch '" + marker + "'"));
        List<SyncPlan> plans = runner.plan(job, Cancellation.none());
        RunRecord record = runner.apply(job, plans, "manual", p -> { }, Cancellation.none());
        assertEquals(RunResult.OK, record.result());
        assertEquals(1, record.added());
        assertEquals("la la", Files.readString(stick.resolve("Music/song.mp3")));
        assertFalse(Files.exists(stick.resolve("Music/list.m3u")));
        assertTrue(Files.exists(marker));
        assertEquals(record.started(), history.last("music").orElseThrow().started());
        assertTrue(Files.readString(history.log("music")).contains("$ true"));
    }

    @Test
    void aFailingCommandBeforeStopsTheSync() throws Exception {
        Job job = job(new SyncOptions(true, false, false, false, "exit 3", true, ""));
        RunRecord record = runner.apply(job, runner.plan(job, Cancellation.none()), "manual", p -> { },
                Cancellation.none());
        assertEquals(RunResult.FAILED, record.result());
        assertEquals("before-failed", record.detail());
        assertFalse(Files.exists(stick.resolve("Music/song.mp3")));
    }

    @Test
    void aMissingStickIsAnError() {
        Job job = Job.sync("x", "X", List.of(new FolderPair(music, dir.resolve("unplugged/stick/Music"))),
                SyncOptions.DEFAULT);
        assertThrows(IOException.class, () -> runner.plan(job, Cancellation.none()));
    }
}

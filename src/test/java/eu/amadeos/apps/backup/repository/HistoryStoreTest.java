package eu.amadeos.apps.backup.repository;

import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HistoryStoreTest {
    @TempDir
    Path dir;

    static RunRecord ok(String time) {
        return new RunRecord(Instant.parse(time), Duration.ofSeconds(95), RunResult.OK, "schedule", 12000, 12, 3, 1,
                1_500_000, "4f2a9c1d", "");
    }

    @Test
    void runsAreKeptNewestFirstWithTheLastLog() throws Exception {
        HistoryStore store = new HistoryStore(dir);
        RunRecord first = ok("2026-10-24T21:00:00Z");
        RunRecord failed = RunRecord.failed(Instant.parse("2026-10-25T21:00:00Z"), Duration.ZERO, "manual",
                RunResult.TARGET_MISSING, "BACKUP");
        store.add("home", first, "log 1");
        store.add("home", failed, "log 2");
        assertEquals(List.of(failed, first), new HistoryStore(dir).runs("home"));
        assertEquals(Optional.of(failed), store.last("home"));
        assertEquals(Optional.of(first), store.lastSaved("home"));
        assertEquals("log 2", Files.readString(store.log("home")));
    }

    @Test
    void theHistoryIsCappedAndGoesWithTheJob() throws Exception {
        HistoryStore store = new HistoryStore(dir);
        for (int i = 0; i < HistoryStore.KEEP + 5; i++) {
            store.add("sync", ok(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L).toString()), null);
        }
        assertEquals(HistoryStore.KEEP, store.runs("sync").size());
        store.removeJob("sync");
        assertFalse(Files.exists(store.directory("sync")));
        assertEquals(List.of(), store.runs("sync"));
    }
}

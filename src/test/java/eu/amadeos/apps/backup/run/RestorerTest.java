package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.engine.BackupRequest;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.engine.restic.ResticEngine;
import eu.amadeos.apps.backup.sync.Trash;
import eu.amadeos.commons.process.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestorerTest {
    @TempDir
    Path dir;
    Path docs;
    ResticEngine engine;
    Repository repo;
    String snapshot;
    Restorer restorer;

    @BeforeEach
    void setUp() throws Exception {
        docs = Files.createDirectories(dir.resolve("home/Documents"));
        Files.writeString(docs.resolve("report.pdf"), "version 1");
        Files.createDirectories(docs.resolve("letters"));
        Files.writeString(docs.resolve("letters/ada.txt"), "Dear Ada");
        engine = new ResticEngine(CommandRunner.system());
        repo = new Repository(dir.resolve("repo"), "a good password");
        engine.init(repo);
        BackupSummary s = engine.backup(repo, new BackupRequest(List.of(docs), List.of(), BackupOptions.DEFAULT, List.of()),
                p -> { }, Cancellation.none());
        snapshot = s.snapshotId();
        restorer = new Restorer(engine, new Trash(dir.resolve("trash"), dir, "1000"), dir.resolve("cache"));
    }

    private SnapshotEntry entry(String name) throws Exception {
        return engine.list(repo, snapshot, docs.toString()).stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow();
    }

    private Restorer.Result restore(Path destination, Restorer.Conflicts conflicts, SnapshotEntry... entries)
            throws Exception {
        return restorer.restore(repo, snapshot, List.of(entries), destination, conflicts, p -> { }, Cancellation.none());
    }

    @Test
    void aDeletedFileComesBackToItsPlace() throws Exception {
        Files.delete(docs.resolve("report.pdf"));
        assertEquals(List.of(), Restorer.existing(List.of(entry("report.pdf")), null));
        Restorer.Result r = restore(null, Restorer.Conflicts.SKIP, entry("report.pdf"));
        assertEquals(1, r.restored());
        assertEquals("version 1", Files.readString(docs.resolve("report.pdf")));
        try (var left = Files.list(docs)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith(".amade-restore")));
        }
    }

    @Test
    void conflictsAreSkippedKeptBesideOrReplacedIntoTheTrash() throws Exception {
        Files.writeString(docs.resolve("report.pdf"), "version 2");
        SnapshotEntry report = entry("report.pdf");
        assertEquals(List.of(docs.resolve("report.pdf")), Restorer.existing(List.of(report), null));

        assertEquals(1, restore(null, Restorer.Conflicts.SKIP, report).skipped());
        assertEquals("version 2", Files.readString(docs.resolve("report.pdf")));

        restore(null, Restorer.Conflicts.KEEP_BOTH, report);
        assertEquals("version 1", Files.readString(docs.resolve("report (restored).pdf")));
        assertEquals("version 2", Files.readString(docs.resolve("report.pdf")));

        restore(null, Restorer.Conflicts.REPLACE, report);
        assertEquals("version 1", Files.readString(docs.resolve("report.pdf")));
        assertEquals("version 2", Files.readString(dir.resolve("trash/files/report.pdf")));
    }

    @Test
    void foldersAreMergedAndCanGoElsewhere() throws Exception {
        Files.writeString(docs.resolve("letters/new.txt"), "keep me");
        Files.delete(docs.resolve("letters/ada.txt"));
        restore(null, Restorer.Conflicts.SKIP, entry("letters"));
        assertEquals("Dear Ada", Files.readString(docs.resolve("letters/ada.txt")));
        assertEquals("keep me", Files.readString(docs.resolve("letters/new.txt")));

        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        restore(elsewhere, Restorer.Conflicts.SKIP, entry("letters"), entry("report.pdf"));
        assertEquals("Dear Ada", Files.readString(elsewhere.resolve("letters/ada.txt")));
        assertTrue(Files.exists(elsewhere.resolve("report.pdf")));
        assertFalse(Files.exists(elsewhere.resolve("home")));
    }

    @Test
    void freeNamesCountUp() throws Exception {
        Path f = Files.writeString(dir.resolve("a.txt"), "x");
        assertEquals(dir.resolve("a (restored).txt"), Restorer.freeName(f));
        Files.writeString(dir.resolve("a (restored).txt"), "x");
        assertEquals(dir.resolve("a (restored 2).txt"), Restorer.freeName(f));
        assertEquals(dir.resolve("Makefile (restored)"), Restorer.freeName(dir.resolve("Makefile")));
    }
}

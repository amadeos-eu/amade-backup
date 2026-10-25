package eu.amadeos.apps.backup.repository;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStoreTest {
    @TempDir
    Path dir;

    static Job home() {
        return Job.home("home-folder", "Home folder", Path.of("/home/ralf"),
                Target.onDisk("1234-ABCD", "BACKUP", Path.of("amadeos-backup/home-folder")),
                EnumSet.of(HomeArea.DOCUMENTS, HomeArea.SETTINGS), List.of("*.iso", "~/tmp"));
    }

    @Test
    void everyKindOfJobSurvivesSavingAndLoading() throws Exception {
        Job home = home().withKeep(new KeepPolicy(1, 2, 3, 4, 5))
                .withSchedule(new Schedule(Schedule.Frequency.WEEKLY, LocalTime.of(7, 30), DayOfWeek.FRIDAY, false))
                .withOptions(new BackupOptions(false, true, 4_000_000_000L));
        Job backup = Job.backup("photos", "Photos", List.of(Path.of("/home/ralf/Pictures"), Path.of("/srv/photos")),
                Target.folder(Path.of("/mnt/nas/amadeos-backup/photos")));
        Job sync = Job.sync("music", "Music to stick", List.of(new FolderPair(Path.of("/home/ralf/Music"),
                Path.of("/media/ralf/STICK/Music"))), new SyncOptions(false, true, true, true, "mount /x", false, "sync"))
                .withExcludes(List.of("*.m3u"));
        JobStore store = new JobStore(dir.resolve("jobs.json"));
        store.save(List.of(home, backup, sync));
        assertEquals(List.of(home, backup, sync), new JobStore(dir.resolve("jobs.json")).load());
    }

    @Test
    void putReplacesByIdAndRemoveForgets() throws Exception {
        JobStore store = new JobStore(dir.resolve("sub/jobs.json"));
        assertEquals(List.of(), store.load());
        store.put(home());
        store.put(home().withName("Renamed"));
        assertEquals(1, store.load().size());
        assertEquals("Renamed", store.find("home-folder").orElseThrow().name());
        store.remove("home-folder");
        assertEquals(List.of(), store.load());
    }

    @Test
    void aBrokenFileGivesNoJobsInsteadOfACrash() throws Exception {
        Path file = dir.resolve("jobs.json");
        Files.writeString(file, "{ not json");
        assertEquals(List.of(), new JobStore(file).load());
        Files.writeString(file, "{\"version\":1,\"jobs\":[{\"name\":\"no id or kind\"}]}");
        assertEquals(List.of(), new JobStore(file).load());
    }

    @Test
    void newIdsComeFromTheNameAndAreUnique() {
        assertEquals("home-folder", JobStore.newId("Home folder", List.of()));
        assertEquals("personlicher-ordner", JobStore.newId("Persönlicher Ordner", List.of()));
        assertEquals("home-folder-2", JobStore.newId("Home folder", List.of(home())));
        assertEquals("job", JobStore.newId("???", List.of()));
        assertTrue(JobStore.newId("x".repeat(100), List.of()).length() <= 40);
    }
}

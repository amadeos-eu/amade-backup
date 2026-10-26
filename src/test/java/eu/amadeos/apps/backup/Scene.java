package eu.amadeos.apps.backup;

import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.engine.restic.ResticEngine;
import eu.amadeos.apps.backup.repository.HistoryStore;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.BackupRunner;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.Readme;
import eu.amadeos.apps.backup.run.Restorer;
import eu.amadeos.apps.backup.run.SyncRunner;
import eu.amadeos.apps.backup.sync.SyncExecutor;
import eu.amadeos.apps.backup.sync.SyncPlanner;
import eu.amadeos.apps.backup.sync.Trash;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.FinishedProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A home folder with documents, pictures and music, a backup disk with a restic repository holding five
 * backups from September to today, and the jobs and history that go with them: what the window tests and
 * the screenshots show. "Today" is Sunday 25 October 2026, 21:30 in Berlin.
 */
public final class Scene {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    public static final Instant NOW = Instant.parse("2026-10-25T20:30:00Z");
    public static final String PASSWORD = "k7mqa-3xv9p-wd4nh-r8tse-2bcfy-qj6ua";
    public static final String DISK_UUID = "3F2A-91C4";

    public final Path home;
    public final Path disk;
    public final Path stick;
    public final Clock clock = Clock.fixed(NOW, ZONE);
    public final XdgDirectories xdg;
    public final Disks disks;
    public final Places places;
    public final Passwords passwords = new Passwords(new SessionPasswordStore());
    public final ResticEngine engine = new ResticEngine(CommandRunner.system());
    public final JobStore jobs;
    public final HistoryStore history;
    public final Trash trash;
    public final BackupRunner backups;
    public final SyncRunner syncs;
    public final Restorer restorer;
    public final Job homeJob;
    public final Job syncJob;
    public final Job nasJob;
    public final Repository repository;

    /**
     * @param work  a folder for the state, the trash and the stick
     * @param home  the home folder to fill
     * @param disk  where the backup disk is mounted; it shows as disk "BACKUP" if it lies under /media
     */
    public Scene(Path work, Path home, Path disk, Messages msg) throws Exception {
        this.home = home;
        this.disk = disk;
        // The car stick sits next to the backup disk, as a second mounted stick would.
        this.stick = disk.resolveSibling("STICK");
        Path dev = Files.createDirectories(work.resolve("dev"));
        Path sdb1 = dev.resolve("sdb1");
        if (!Files.exists(sdb1)) Files.createFile(sdb1);
        Path byUuid = Files.createDirectories(work.resolve("by-uuid"));
        Path byLabel = Files.createDirectories(work.resolve("by-label"));
        if (!Files.exists(byUuid.resolve(DISK_UUID), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(byUuid.resolve(DISK_UUID), sdb1);
            Files.createSymbolicLink(byLabel.resolve("BACKUP"), sdb1);
        }
        Files.writeString(work.resolve("mounts"), sdb1 + " " + disk + " ext4 rw,nosuid,nodev 0 0\n");
        disks = new Disks(work.resolve("mounts"), byUuid, byLabel);
        places = new Places(disks);
        xdg = new XdgDirectories(Map.of(), home.toString());
        jobs = new JobStore(work.resolve("config/jobs.json"));
        history = new HistoryStore(work.resolve("state"));
        trash = new Trash(work.resolve("trash"), work, "1000");
        backups = new BackupRunner(engine, places, passwords, history, command -> FinishedProcess.failing(1, ""), home,
                home.resolve(".local/share"), new Readme(msg, msg), clock);
        syncs = new SyncRunner(new SyncPlanner(trash), new SyncExecutor(trash), history, CommandRunner.system(), home,
                clock);
        restorer = new Restorer(engine, trash, work.resolve("cache"));

        Target target = places.targetFor(disk.resolve(Places.FOLDER).resolve("home-folder"));
        homeJob = Job.home("home-folder", "Home folder", home, target, HomeArea.defaults(), List.of("*.iso"));
        syncJob = Job.sync("music-to-car-stick", "Music to car stick",
                List.of(new FolderPair(home.resolve("Music"), stick.resolve("Music"))), SyncOptions.DEFAULT);
        nasJob = Job.backup("photos-to-nas", "Photos to NAS", List.of(home.resolve("Pictures")),
                Target.onDisk("77AB-0001", "NAS", Path.of("amadeos-backup/photos-to-nas")));
        repository = new Repository(disk.resolve(Places.FOLDER).resolve("home-folder"), PASSWORD);
        build();
    }

    private void build() throws Exception {
        Files.createDirectories(disk);
        Files.createDirectories(stick);
        write("Documents/Letters/to the landlord.odt", 18_400, "2026-08-03T10:00:00Z");
        write("Documents/Letters/insurance claim.pdf", 92_000, "2026-09-01T10:00:00Z");
        write("Documents/tax return 2025.pdf", 412_000, "2026-05-12T10:00:00Z");
        write("Documents/report.odt", 56_000, "2026-09-10T10:00:00Z");
        write("Documents/garden plan.ods", 21_000, "2026-04-20T10:00:00Z");
        write("Pictures/Holidays 2026/beach.jpg", 2_400_000, "2026-07-14T10:00:00Z");
        write("Pictures/Holidays 2026/harbour.jpg", 2_100_000, "2026-07-15T10:00:00Z");
        write("Pictures/Holidays 2026/sunset.jpg", 1_900_000, "2026-07-16T10:00:00Z");
        write("Music/Bach/Goldberg Variations.flac", 210_000, "2025-12-01T10:00:00Z");
        write("Music/Satie/Gymnopedies.flac", 120_000, "2025-12-01T10:00:00Z");
        write(".config/amadeos/settings.properties", 400, "2026-09-01T10:00:00Z");
        write(".local/share/amadeos/mail/inbox.mbox", 840_000, "2026-09-12T10:00:00Z");
        write("Videos/film.mkv", 3_000_000, "2026-06-01T10:00:00Z");

        engine.init(repository);
        passwords.store(homeJob, PASSWORD);
        new Readme(eu.amadeos.apps.backup.TestTexts.EN, eu.amadeos.apps.backup.TestTexts.EN).write(homeJob,
                repository.location());
        snapshot("2026-09-14T19:00:00Z", 41, 52_000_000);
        write("Documents/Letters/to the bank.odt", 15_000, "2026-09-30T10:00:00Z");
        snapshot("2026-10-02T19:00:00Z", 38, 210_000);
        write("Documents/report.odt", 61_000, "2026-10-19T10:00:00Z");
        snapshot("2026-10-20T19:00:00Z", 44, 380_000);
        write("Documents/Letters/to the school.odt", 12_000, "2026-10-23T10:00:00Z");
        snapshot("2026-10-24T19:00:00Z", 39, 140_000);
        write("Documents/report.odt", 64_000, "2026-10-25T07:50:00Z");
        snapshot("2026-10-25T08:12:00Z", 42, 160_000);
        // After the last backup: a changed report, a deleted letter.
        write("Documents/report.odt", 66_000, "2026-10-25T16:00:00Z");
        Files.delete(home.resolve("Documents/Letters/insurance claim.pdf"));

        jobs.save(List.of(homeJob, syncJob, nasJob));
        history.add(syncJob.id(), new RunRecord(Instant.parse("2026-10-18T16:02:00Z"), Duration.ofSeconds(48), RunResult.OK,
                "manual", 214, 12, 3, 1, 61_000_000, "", ""), null);
        history.add(nasJob.id(), new RunRecord(Instant.parse("2026-10-11T19:00:00Z"), Duration.ofMinutes(7), RunResult.OK,
                "schedule", 3120, 45, 0, 0, 88_000_000, "", ""), null);
        history.add(nasJob.id(), RunRecord.failed(Instant.parse("2026-10-25T19:00:00Z"), Duration.ZERO, "schedule",
                RunResult.TARGET_MISSING, "NAS"), null);
    }

    private void write(String relative, int size, String modified) throws IOException {
        Path p = home.resolve(relative);
        Files.createDirectories(p.getParent());
        byte[] bytes = new byte[size];
        new Random(relative.hashCode() + size).nextBytes(bytes);
        Files.write(p, bytes);
        Files.setLastModifiedTime(p, FileTime.from(Instant.parse(modified)));
        // The folders get the time of their newest change, not the real clock of the run.
        for (Path dir = p.getParent(); !dir.equals(home); dir = dir.getParent()) {
            Files.setLastModifiedTime(dir, FileTime.from(Instant.parse(modified)));
        }
    }

    /** A backup at a given time, which only restic itself can set; recorded in the history like a real run. */
    // --8<-- [start:snapshot]
    private void snapshot(String time, int seconds, long added) throws Exception {
        String local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.parse(time).atZone(ZoneId.systemDefault()));
        ProcessBuilder pb = new ProcessBuilder("restic", "--repo", repository.location().toString(), "backup", "--quiet",
                "--time", local, "--tag", "amadeos-job:" + homeJob.id(), "--exclude", home.resolve("Videos").toString(),
                home.toString());
        pb.environment().put("RESTIC_PASSWORD", PASSWORD);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("restic backup failed: " + out);
        String id = engine.snapshots(repository).getLast().id();
        history.add(homeJob.id(), new RunRecord(Instant.parse(time), Duration.ofSeconds(seconds), RunResult.OK,
                "schedule", 13, 1, 1, 0, added, id, ""), null);
    }
    // --8<-- [end:snapshot]
}

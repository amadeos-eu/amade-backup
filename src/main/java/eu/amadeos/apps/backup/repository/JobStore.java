package eu.amadeos.apps.backup.repository;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.commons.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The jobs in {@code ~/.config/amadeos/backup/jobs.json}, written for people to read. The file is replaced as
 * a whole, through a temporary file, so a crash never leaves half of it; unknown or broken values fall back to
 * the defaults, a broken job is skipped.
 */
public class JobStore {
    public static final String FILE = "jobs.json";
    private static final Logger LOG = LoggerFactory.getLogger(JobStore.class);

    private final Path file;

    public JobStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized List<Job> load() {
        if (!Files.isRegularFile(file)) return List.of();
        Object root;
        try {
            root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Cannot read {}", file, e);
            return List.of();
        }
        List<Job> jobs = new ArrayList<>();
        for (Object o : Json.list(root, "jobs")) {
            try {
                jobs.add(job(o));
            } catch (RuntimeException e) {
                LOG.warn("Skipping a broken job in {}: {}", file, e.getMessage());
            }
        }
        return jobs;
    }

    public synchronized void save(List<Job> jobs) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        List<Object> list = new ArrayList<>();
        for (Job job : jobs) list.add(json(job));
        root.put("jobs", list);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<Job> find(String id) {
        return load().stream().filter(j -> j.id().equals(id)).findFirst();
    }

    /** Adds the job or replaces the one with its id. */
    public synchronized void put(Job job) throws IOException {
        List<Job> jobs = new ArrayList<>(load());
        boolean replaced = false;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id().equals(job.id())) {
                jobs.set(i, job);
                replaced = true;
            }
        }
        if (!replaced) jobs.add(job);
        save(jobs);
    }

    public synchronized void remove(String id) throws IOException {
        List<Job> jobs = new ArrayList<>(load());
        jobs.removeIf(j -> j.id().equals(id));
        save(jobs);
    }

    /** A new id from the name ("Home folder" to "home-folder"), unique among the existing jobs. */
    public static String newId(String name, List<Job> existing) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "job";
        if (base.length() > 40) base = base.substring(0, 40).replaceAll("-+$", "");
        Set<String> taken = new java.util.HashSet<>();
        for (Job j : existing) taken.add(j.id());
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "-" + n;
        return id;
    }

    // ---- JSON ---------------------------------------------------------------------------------------------

    static Map<String, Object> json(Job job) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", job.id());
        o.put("name", job.name());
        o.put("kind", job.kind().key());
        o.put("sources", job.sources().stream().map(Path::toString).toList());
        if (job.target() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("disk-uuid", job.target().diskUuid());
            t.put("disk-label", job.target().diskLabel());
            t.put("path", job.target().path().toString());
            o.put("target", t);
        }
        o.put("home-areas", job.homeAreas().stream().sorted().map(HomeArea::key).toList());
        o.put("excludes", job.excludes());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("exclude-caches", job.options().excludeCaches());
        options.put("one-file-system", job.options().oneFileSystem());
        options.put("max-file-size", job.options().maxFileSize());
        o.put("options", options);
        KeepPolicy k = job.keep();
        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("within-days", k.withinDays());
        keep.put("daily", k.daily());
        keep.put("weekly", k.weekly());
        keep.put("monthly", k.monthly());
        keep.put("yearly", k.yearly());
        o.put("keep", keep);
        Schedule s = job.schedule();
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("frequency", s.frequency().key());
        schedule.put("time", s.time().toString());
        schedule.put("day", s.day().name().toLowerCase(Locale.ROOT));
        schedule.put("when-plugged-in", s.whenPluggedIn());
        o.put("schedule", schedule);
        List<Object> pairs = new ArrayList<>();
        for (FolderPair p : job.pairs()) {
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("source", p.source().toString());
            pair.put("target", p.target().toString());
            pairs.add(pair);
        }
        o.put("pairs", pairs);
        SyncOptions y = job.sync();
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("mirror", y.mirror());
        sync.put("ignore-existing", y.ignoreExisting());
        sync.put("size-only", y.sizeOnly());
        sync.put("skip-newer", y.skipNewer());
        sync.put("before", y.before());
        sync.put("stop-if-before-fails", y.stopIfBeforeFails());
        sync.put("after", y.after());
        o.put("sync", sync);
        return o;
    }

    static Job job(Object o) {
        String id = Json.str(o, "id");
        if (id.isBlank()) throw new IllegalArgumentException("Job without id");
        JobKind kind = "sync".equals(Json.str(o, "kind")) ? JobKind.SYNC : JobKind.BACKUP;
        List<Path> sources = Json.list(o, "sources").stream().map(s -> Path.of(s.toString())).toList();
        Target target = null;
        Object t = Json.member(o, "target");
        if (t != null) {
            String path = Json.str(t, "path");
            target = new Target(Json.str(t, "disk-uuid"), Json.str(t, "disk-label"), Path.of(path));
        }
        Set<HomeArea> areas = EnumSet.noneOf(HomeArea.class);
        for (Object a : Json.list(o, "home-areas")) {
            for (HomeArea area : HomeArea.values()) if (area.key().equals(a.toString())) areas.add(area);
        }
        List<String> excludes = Json.list(o, "excludes").stream().map(Object::toString).toList();
        Object op = Json.member(o, "options");
        BackupOptions d = BackupOptions.DEFAULT;
        BackupOptions options = new BackupOptions(flag(op, "exclude-caches", d.excludeCaches()),
                flag(op, "one-file-system", d.oneFileSystem()), Math.max(0, longNumber(op, "max-file-size", 0)));
        Object kp = Json.member(o, "keep");
        KeepPolicy dk = KeepPolicy.DEFAULT;
        KeepPolicy keep = kp == null ? dk : new KeepPolicy(count(kp, "within-days", dk.withinDays()),
                count(kp, "daily", dk.daily()), count(kp, "weekly", dk.weekly()), count(kp, "monthly", dk.monthly()),
                count(kp, "yearly", dk.yearly()));
        Object sc = Json.member(o, "schedule");
        Schedule schedule = sc == null ? (kind == JobKind.SYNC ? Schedule.MANUAL : Schedule.DEFAULT) : schedule(sc);
        List<FolderPair> pairs = new ArrayList<>();
        for (Object p : Json.list(o, "pairs")) {
            pairs.add(new FolderPair(Path.of(Json.str(p, "source")), Path.of(Json.str(p, "target"))));
        }
        Object sy = Json.member(o, "sync");
        SyncOptions ds = SyncOptions.DEFAULT;
        SyncOptions sync = sy == null ? ds : new SyncOptions(flag(sy, "mirror", ds.mirror()),
                flag(sy, "ignore-existing", ds.ignoreExisting()), flag(sy, "size-only", ds.sizeOnly()),
                flag(sy, "skip-newer", ds.skipNewer()), Json.str(sy, "before"),
                flag(sy, "stop-if-before-fails", ds.stopIfBeforeFails()), Json.str(sy, "after"));
        return new Job(id, Json.str(o, "name"), kind, sources, target, areas, excludes, options, keep, schedule, pairs,
                sync);
    }

    private static Schedule schedule(Object sc) {
        Schedule d = Schedule.DEFAULT;
        Schedule.Frequency frequency = d.frequency();
        for (Schedule.Frequency f : Schedule.Frequency.values()) if (f.key().equals(Json.str(sc, "frequency"))) frequency = f;
        LocalTime time = d.time();
        try {
            time = LocalTime.parse(Json.str(sc, "time"));
        } catch (DateTimeParseException e) {
            // the default
        }
        DayOfWeek day = d.day();
        try {
            day = DayOfWeek.valueOf(Json.str(sc, "day").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // the default
        }
        return new Schedule(frequency, time, day, flag(sc, "when-plugged-in", d.whenPluggedIn()));
    }

    private static boolean flag(Object o, String key, boolean fallback) {
        return Json.member(o, key) instanceof Boolean b ? b : fallback;
    }

    private static long longNumber(Object o, String key, long fallback) {
        return Json.member(o, key) instanceof Number n ? n.longValue() : fallback;
    }

    private static int count(Object o, String key, int fallback) {
        return (int) Math.max(0, Math.min(1000, longNumber(o, key, fallback)));
    }
}

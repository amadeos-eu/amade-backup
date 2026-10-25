package eu.amadeos.apps.backup.repository;

import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.commons.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runs of each job, newest first, in {@code ~/.local/state/amadeos/backup/<job>/history.json}, and the
 * log of the last run next to it. Kept for the last {@value #KEEP} runs.
 */
public class HistoryStore {
    static final int KEEP = 500;
    private static final Logger LOG = LoggerFactory.getLogger(HistoryStore.class);

    private final Path directory;

    public HistoryStore(Path directory) {
        this.directory = directory;
    }

    public Path directory(String jobId) {
        return directory.resolve(jobId);
    }

    public Path log(String jobId) {
        return directory(jobId).resolve("last-run.log");
    }

    public synchronized List<RunRecord> runs(String jobId) {
        Path file = directory(jobId).resolve("history.json");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<RunRecord> runs = new ArrayList<>();
            for (Object o : Json.list(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "runs")) {
                RunRecord r = record(o);
                if (r != null) runs.add(r);
            }
            return runs;
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Cannot read {}", file, e);
            return List.of();
        }
    }

    public Optional<RunRecord> last(String jobId) {
        return runs(jobId).stream().findFirst();
    }

    /** The newest run that saved something. */
    public Optional<RunRecord> lastSaved(String jobId) {
        return runs(jobId).stream().filter(r -> r.result().saved()).findFirst();
    }

    public synchronized void add(String jobId, RunRecord run, String log) throws IOException {
        List<RunRecord> runs = new ArrayList<>(runs(jobId));
        runs.addFirst(run);
        if (runs.size() > KEEP) runs = runs.subList(0, KEEP);
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (RunRecord r : runs) list.add(json(r));
        root.put("runs", list);
        Path dir = directory(jobId);
        Files.createDirectories(dir);
        write(dir.resolve("history.json"), Json.write(root));
        if (log != null) write(log(jobId), log);
    }

    public synchronized void removeJob(String jobId) throws IOException {
        Path dir = directory(jobId);
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
        }
        Files.deleteIfExists(dir);
    }

    private static void write(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Map<String, Object> json(RunRecord r) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("started", r.started().toString());
        o.put("seconds", r.took().toMillis() / 1000.0);
        o.put("result", r.result().key());
        o.put("trigger", r.trigger());
        o.put("files", r.files());
        o.put("added", r.added());
        o.put("changed", r.changed());
        o.put("removed", r.removed());
        o.put("bytes", r.bytes());
        o.put("snapshot", r.snapshot());
        o.put("detail", r.detail());
        return o;
    }

    static RunRecord record(Object o) {
        Instant started;
        try {
            started = Instant.parse(Json.str(o, "started"));
        } catch (DateTimeParseException e) {
            return null;
        }
        RunResult result = RunResult.FAILED;
        for (RunResult r : RunResult.values()) if (r.key().equals(Json.str(o, "result"))) result = r;
        double seconds = Json.member(o, "seconds") instanceof Number n ? n.doubleValue() : 0;
        return new RunRecord(started, Duration.ofMillis((long) (seconds * 1000)), result, Json.str(o, "trigger"),
                number(o, "files"), number(o, "added"), number(o, "changed"), number(o, "removed"), number(o, "bytes"),
                Json.str(o, "snapshot"), Json.str(o, "detail"));
    }

    private static long number(Object o, String key) {
        return Json.member(o, key) instanceof Number n ? n.longValue() : 0;
    }
}

package eu.amadeos.apps.backup.agent;

import eu.amadeos.commons.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the helper remembers between sessions, per job, in {@code ~/.local/state/amadeos/backup/agent.json}:
 * since when it knows the job, when it last reminded the user of it and when it last checked its backups.
 */
public final class AgentState {
    public static final String FILE = "agent.json";
    private static final Logger LOG = LoggerFactory.getLogger(AgentState.class);
    private static final String KNOWN = "known-since";
    private static final String REMINDED = "reminded";
    private static final String CHECKED = "checked";

    private final Path file;
    private final Map<String, Map<String, Instant>> jobs = new LinkedHashMap<>();

    public AgentState(Path file) {
        this.file = file;
        load();
    }

    /** When the helper first saw the job; {@code now} for a job it has not seen before. */
    public synchronized Instant knownSince(String jobId, Instant now) {
        return jobs.computeIfAbsent(jobId, id -> new LinkedHashMap<>()).computeIfAbsent(KNOWN, k -> now);
    }

    public synchronized Optional<Instant> reminded(String jobId) {
        return get(jobId, REMINDED);
    }

    public synchronized void setReminded(String jobId, Instant when) {
        jobs.computeIfAbsent(jobId, id -> new LinkedHashMap<>()).put(REMINDED, when);
    }

    public synchronized Optional<Instant> checked(String jobId) {
        return get(jobId, CHECKED);
    }

    public synchronized void setChecked(String jobId, Instant when) {
        jobs.computeIfAbsent(jobId, id -> new LinkedHashMap<>()).put(CHECKED, when);
    }

    /** Forgets the jobs that no longer exist. */
    public synchronized void retain(java.util.Collection<String> jobIds) {
        jobs.keySet().retainAll(jobIds);
    }

    private Optional<Instant> get(String jobId, String key) {
        Map<String, Instant> job = jobs.get(jobId);
        return job == null ? Optional.empty() : Optional.ofNullable(job.get(key));
    }

    public synchronized void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> list = new LinkedHashMap<>();
        jobs.forEach((id, values) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            values.forEach((k, v) -> o.put(k, v.toString()));
            list.put(id, o);
        });
        root.put("jobs", list);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Cannot write {}", file, e);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(Json.member(root, "jobs") instanceof Map<?, ?> list)) return;
            list.forEach((id, values) -> {
                Map<String, Instant> job = new LinkedHashMap<>();
                for (String key : new String[]{KNOWN, REMINDED, CHECKED}) {
                    try {
                        String text = Json.str(values, key);
                        if (!text.isEmpty()) job.put(key, Instant.parse(text));
                    } catch (DateTimeParseException e) {
                        // left out
                    }
                }
                jobs.put(String.valueOf(id), job);
            });
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Cannot read {}", file, e);
        }
    }
}

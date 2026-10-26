package eu.amadeos.apps.backup.engine.restic;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.BackupSummary;
import eu.amadeos.apps.backup.domain.FoundFile;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.BackupRequest;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.DiffStats;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.commons.json.Json;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramMissingException;
import eu.amadeos.commons.process.ProgramResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The backup engine on top of restic: every call is one run of the {@code restic} program with {@code --json},
 * whose output is read line by line. restic's repository format is open and documented, so the backups can
 * be read with restic anywhere, without amadeOS.
 */
public final class ResticEngine implements BackupEngine {
    /** restic's exit code for a backup that could not read some files but saved the rest. */
    static final int INCOMPLETE = 3;

    private final CommandRunner runner;
    private final String program;

    public ResticEngine(CommandRunner runner) {
        this(runner, "restic");
    }

    public ResticEngine(CommandRunner runner, String program) {
        this.runner = runner;
        this.program = program;
    }

    @Override
    public String version() throws EngineException {
        try {
            ProgramResult result = runner.run(List.of(program, "version"), Duration.ofSeconds(10));
            if (!result.succeeded()) throw new EngineException(EngineException.Kind.FAILED, result.failure(program));
            String text = result.stdoutText().strip();
            int compiled = text.indexOf(" compiled");
            return compiled > 0 ? text.substring(0, compiled) : text;
        } catch (ProgramMissingException e) {
            throw new EngineException(EngineException.Kind.PROGRAM_MISSING, "restic is not installed", e);
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException(EngineException.Kind.CANCELLED, "Interrupted", e);
        }
    }

    @Override
    public boolean isRepository(Path location) {
        return Files.isRegularFile(location.resolve("config")) && Files.isDirectory(location.resolve("keys"));
    }

    @Override
    public void init(Repository repository) throws EngineException {
        try {
            Files.createDirectories(repository.location().getParent());
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, "Cannot create " + repository.location().getParent()
                    + ": " + e.getMessage(), e);
        }
        run(repository, List.of("init"), line -> { }, Cancellation.none());
    }

    // --8<-- [start:backup]
    @Override
    public BackupSummary backup(Repository repository, BackupRequest request, Consumer<BackupProgress> progress,
                                Cancellation cancellation) throws EngineException {
        List<String> args = new ArrayList<>(List.of("backup", "--json"));
        if (request.options().excludeCaches()) args.add("--exclude-caches");
        if (request.options().oneFileSystem()) args.add("--one-file-system");
        if (request.options().maxFileSize() > 0) {
            args.addAll(List.of("--exclude-larger-than", request.options().maxFileSize() + "b"));
        }
        for (String exclude : request.excludes()) args.addAll(List.of("--exclude", exclude));
        for (String tag : request.tags()) args.addAll(List.of("--tag", tag));
        for (Path source : request.sources()) args.add(source.toAbsolutePath().toString());

        Map<String, Object>[] summary = new Map[1];
        ResticCall.Ending ending = ResticCall.run(runner, program, repository, args, ResticCall.Output.lines(line -> {
            Map<String, Object> message = object(line);
            if (message == null) return;
            switch (Json.str(message, "message_type")) {
                case "status" -> progress.accept(new BackupProgress(number(message, "percent_done"),
                        (long) number(message, "files_done"), (long) number(message, "total_files"),
                        (long) number(message, "bytes_done"), (long) number(message, "total_bytes"),
                        Json.list(message, "current_files").stream().map(Object::toString).toList()));
                case "summary" -> summary[0] = message;
                default -> { }
            }
        }), cancellation, INCOMPLETE);
        if (summary[0] == null) {
            throw new EngineException(EngineException.Kind.FAILED, "restic did not report a backup: " + ending.errorText());
        }
        Map<String, Object> s = summary[0];
        return new BackupSummary(Json.str(s, "snapshot_id"), (long) number(s, "files_new"),
                (long) number(s, "files_changed"), (long) number(s, "files_unmodified"),
                (long) number(s, "total_files_processed"), (long) number(s, "total_bytes_processed"),
                (long) number(s, "data_added"), Duration.ofMillis((long) (number(s, "total_duration") * 1000)),
                errors(ending));
    }
    // --8<-- [end:backup]

    /** Files restic could not read: JSON error messages ({@code "item"}) or "error: ..." lines. */
    static List<String> errors(ResticCall.Ending ending) {
        List<String> errors = new ArrayList<>();
        for (String line : ending.stderr()) {
            Map<String, Object> message = object(line);
            if (message != null && "error".equals(Json.str(message, "message_type"))) {
                String item = Json.str(message, "item");
                String reason = Json.str(Json.member(message, "error"), "message");
                errors.add(item.isEmpty() ? reason : item + ": " + reason);
            } else if (line.startsWith("error: ")) {
                errors.add(line.substring("error: ".length()).strip());
            }
        }
        return errors;
    }

    @Override
    public List<Snapshot> snapshots(Repository repository) throws EngineException {
        StringBuilder out = new StringBuilder();
        run(repository, List.of("snapshots", "--json"), line -> out.append(line).append('\n'), Cancellation.none());
        List<Snapshot> snapshots = new ArrayList<>();
        for (Object s : array(out.toString())) snapshots.add(snapshot(s));
        snapshots.sort((a, b) -> a.time().compareTo(b.time()));
        return snapshots;
    }

    static Snapshot snapshot(Object s) {
        return new Snapshot(Json.str(s, "id"), Json.str(s, "short_id"), time(Json.str(s, "time")),
                Json.list(s, "paths").stream().map(Object::toString).toList(),
                Json.list(s, "tags").stream().map(Object::toString).toList());
    }

    @Override
    public List<SnapshotEntry> list(Repository repository, String snapshotId, String directory) throws EngineException {
        List<SnapshotEntry> entries = new ArrayList<>();
        String dir = trimSlash(directory);
        run(repository, List.of("ls", "--json", snapshotId, directory), line -> {
            Map<String, Object> node = object(line);
            if (node == null || !"node".equals(Json.str(node, "struct_type"))) return;
            String path = Json.str(node, "path");
            if (trimSlash(path).equals(dir)) return;
            String parent = path.contains("/") ? trimSlash(path.substring(0, path.lastIndexOf('/'))) : "";
            if (!parent.equals(dir)) return;
            entries.add(entry(node));
        }, Cancellation.none());
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    private static String trimSlash(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.equals("/") ? "" : p;
    }

    static SnapshotEntry entry(Object node) {
        return new SnapshotEntry(Json.str(node, "name"), Json.str(node, "path"), type(Json.str(node, "type")),
                (long) number(node, "size"), time(Json.str(node, "mtime")));
    }

    private static SnapshotEntry.Type type(String type) {
        return switch (type) {
            case "dir" -> SnapshotEntry.Type.DIRECTORY;
            case "file" -> SnapshotEntry.Type.FILE;
            case "symlink" -> SnapshotEntry.Type.LINK;
            default -> SnapshotEntry.Type.OTHER;
        };
    }

    @Override
    public List<FoundFile> find(Repository repository, String pattern) throws EngineException {
        StringBuilder out = new StringBuilder();
        run(repository, List.of("find", "--json", pattern), line -> out.append(line).append('\n'), Cancellation.none());
        List<FoundFile> found = new ArrayList<>();
        String text = out.toString().strip();
        if (text.isEmpty()) return found;
        for (Object group : array(text)) {
            String snapshot = Json.str(group, "snapshot");
            for (Object match : Json.list(group, "matches")) {
                found.add(new FoundFile(snapshot, Json.str(match, "path"), type(Json.str(match, "type")),
                        (long) number(match, "size"), time(Json.str(match, "mtime"))));
            }
        }
        return found;
    }

    @Override
    public void restore(Repository repository, String snapshotId, List<String> paths, Path target,
                        Consumer<BackupProgress> progress, Cancellation cancellation) throws EngineException {
        List<String> args = new ArrayList<>(List.of("restore", "--json", snapshotId, "--target", target.toString()));
        for (String path : paths) args.addAll(List.of("--include", path));
        run(repository, args, line -> {
            Map<String, Object> message = object(line);
            if (message != null && "status".equals(Json.str(message, "message_type"))) {
                progress.accept(new BackupProgress(number(message, "percent_done"), (long) number(message, "files_restored"),
                        (long) number(message, "total_files"), (long) number(message, "bytes_restored"),
                        (long) number(message, "total_bytes"), List.of()));
            }
        }, cancellation);
    }

    @Override
    public void dump(Repository repository, String snapshotId, String path, Path file) throws EngineException {
        Path part = file.resolveSibling("." + file.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(part)) {
            // --8<-- [start:dump]
            // --quiet: without it restic writes "reading repository password from stdin" into the file.
            ResticCall.run(runner, program, repository, List.of("dump", "--quiet", snapshotId, path),
                    ResticCall.Output.copyTo(out),
                    Cancellation.none());
            // --8<-- [end:dump]
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, "Cannot write " + file + ": " + e.getMessage(), e);
        } catch (EngineException e) {
            deleteQuietly(part);
            throw e;
        }
        try {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(part);
            throw new EngineException(EngineException.Kind.FAILED, "Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // nothing more to do
        }
    }

    @Override
    public DiffStats diff(Repository repository, String fromSnapshot, String toSnapshot) throws EngineException {
        long[] stats = new long[3];
        run(repository, List.of("diff", "--json", fromSnapshot, toSnapshot), line -> {
            Map<String, Object> message = object(line);
            if (message != null && "statistics".equals(Json.str(message, "message_type"))) {
                stats[0] = (long) number(Json.member(message, "added"), "files");
                stats[1] = (long) number(message, "changed_files");
                stats[2] = (long) number(Json.member(message, "removed"), "files");
            }
        }, Cancellation.none());
        return new DiffStats(stats[0], stats[1], stats[2]);
    }

    // --8<-- [start:forget]
    @Override
    public int forget(Repository repository, KeepPolicy keep, Cancellation cancellation) throws EngineException {
        List<String> args = new ArrayList<>(List.of("forget", "--json", "--group-by", "paths"));
        args.addAll(keep.resticOptions());
        StringBuilder out = new StringBuilder();
        run(repository, args, line -> out.append(line).append('\n'), cancellation);
        int removed = 0;
        String text = out.toString().strip();
        if (!text.isEmpty()) {
            for (Object group : array(text)) removed += Json.list(group, "remove").size();
        }
        if (removed > 0) run(repository, List.of("prune"), line -> { }, cancellation);
        return removed;
    }
    // --8<-- [end:forget]

    @Override
    public List<String> check(Repository repository, int readPercent, Cancellation cancellation) throws EngineException {
        List<String> args = new ArrayList<>(List.of("check"));
        if (readPercent > 0) args.add("--read-data-subset=" + Math.min(100, readPercent) + "%");
        List<String> out = new ArrayList<>();
        try {
            run(repository, args, out::add, cancellation);
            return List.of();
        } catch (EngineException e) {
            if (e.kind() != EngineException.Kind.FAILED) throw e;
            List<String> problems = new ArrayList<>();
            for (String line : out) if (line.toLowerCase().contains("error")) problems.add(line.strip());
            if (problems.isEmpty()) problems.add(e.getMessage());
            return problems;
        }
    }

    private void run(Repository repository, List<String> args, Consumer<String> lines, Cancellation cancellation)
            throws EngineException {
        ResticCall.run(runner, program, repository, args, ResticCall.Output.lines(lines), cancellation);
    }

    // ---- JSON ------------------------------------------------------------------------------------------------

    /** A JSON object on one line, null for anything else (restic mixes in plain text). */
    static Map<String, Object> object(String line) {
        String l = line.strip();
        if (!l.startsWith("{")) return null;
        try {
            return Json.parseObject(l);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The JSON array in restic's output, skipping plain-text lines around it. */
    static List<Object> array(String text) throws EngineException {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < start) return List.of();
        try {
            return Json.parseArray(text.substring(start, end + 1));
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineException.Kind.FAILED, "Unexpected output from restic: " + e.getMessage(), e);
        }
    }

    static double number(Object object, String key) {
        if (Json.member(object, key) instanceof Number n) return n.doubleValue();
        return 0;
    }

    static Instant time(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

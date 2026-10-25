package eu.amadeos.apps.backup.engine.restic;

import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramMissingException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One run of the restic program. The password goes in on standard input, which restic reads when it is not a
 * terminal, so it never shows up in the process list or the environment; the repository is given with
 * {@code --repo}.
 */
final class ResticCall {
    /** What to do with restic's standard output. */
    @FunctionalInterface
    interface Output {
        void read(InputStream stdout) throws IOException;

        static Output lines(Consumer<String> line) {
            return stdout -> {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
                String l;
                while ((l = reader.readLine()) != null) line.accept(l);
            };
        }

        static Output copyTo(OutputStream out) {
            return stdout -> stdout.transferTo(out);
        }
    }

    /** How restic ended: its exit code and the lines it wrote to standard error. */
    record Ending(int exitCode, List<String> stderr) {
        String errorText() {
            return String.join("\n", stderr).strip();
        }
    }

    private ResticCall() {
    }

    static List<String> command(String program, Repository repository, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(program);
        command.add("--repo");
        command.add(repository.location().toString());
        command.addAll(arguments);
        return command;
    }

    /** Runs restic to its end; throws for exit codes other than those in {@code accepted} (0 always). */
    static Ending run(CommandRunner runner, String program, Repository repository, List<String> arguments,
                      Output output, Cancellation cancellation, int... accepted) throws EngineException {
        cancellation.throwIfCancelled();
        List<String> command = command(program, repository, arguments);
        Process process;
        try {
            process = runner.start(command);
        } catch (ProgramMissingException e) {
            throw new EngineException(EngineException.Kind.PROGRAM_MISSING, "restic is not installed", e);
        } catch (IOException e) {
            throw new EngineException(EngineException.Kind.FAILED, "restic could not be started: " + e.getMessage(), e);
        }
        Runnable stop = process::destroy;
        cancellation.onCancel(stop);
        List<String> stderr = new ArrayList<>();
        Thread errReader = Thread.ofVirtual().start(() -> {
            try {
                Output.lines(line -> {
                    synchronized (stderr) {
                        stderr.add(line);
                    }
                }).read(process.getErrorStream());
            } catch (IOException e) {
                // the process ended
            }
        });
        try {
            try (OutputStream in = process.getOutputStream()) {
                in.write((repository.password() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // restic did not wait for a password, or ended already
            }
            try (InputStream out = process.getInputStream()) {
                output.read(out);
            } catch (IOException e) {
                if (!cancellation.isCancelled()) {
                    process.destroy();
                    throw new EngineException(EngineException.Kind.FAILED, "Reading restic's output failed: "
                            + e.getMessage(), e);
                }
            }
            int exit = process.waitFor();
            errReader.join();
            Ending ending;
            synchronized (stderr) {
                ending = new Ending(exit, List.copyOf(stderr));
            }
            if (cancellation.isCancelled()) throw new EngineException(EngineException.Kind.CANCELLED, "Cancelled");
            if (exit == 0) return ending;
            for (int code : accepted) if (code == exit) return ending;
            throw failure(ending);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new EngineException(EngineException.Kind.CANCELLED, "Interrupted", e);
        } finally {
            cancellation.remove(stop);
        }
    }

    /** The kind of a failure from restic's exit code (0.17 and later) or, for older versions, its message. */
    static EngineException failure(Ending ending) {
        String text = ending.errorText();
        String lower = text.toLowerCase();
        EngineException.Kind kind;
        if (ending.exitCode() == 12 || lower.contains("wrong password")) {
            kind = EngineException.Kind.WRONG_PASSWORD;
        } else if (ending.exitCode() == 10 || lower.contains("unable to open config file")
                || lower.contains("is there a repository at the following location")
                || lower.contains("repository does not exist")) {
            kind = EngineException.Kind.NO_REPOSITORY;
        } else if (ending.exitCode() == 11 || lower.contains("unable to create lock") || lower.contains("already locked")) {
            kind = EngineException.Kind.LOCKED;
        } else if (ending.exitCode() == 130) {
            kind = EngineException.Kind.CANCELLED;
        } else {
            kind = EngineException.Kind.FAILED;
        }
        String message = text.isEmpty() ? "restic failed (exit code " + ending.exitCode() + ")" : lastFatal(text);
        return new EngineException(kind, message);
    }

    /** The "Fatal: ..." line restic ends with, else the whole text. */
    private static String lastFatal(String text) {
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.startsWith("Fatal:")) return line.substring("Fatal:".length()).strip();
        }
        return text;
    }
}

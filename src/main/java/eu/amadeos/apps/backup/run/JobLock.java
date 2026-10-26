package eu.amadeos.apps.backup.run;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Makes sure one job runs only once at a time, also when the window and the background helper both start it:
 * a lock on {@code <state>/<job>/running.lock}, released when the run ends or the program dies.
 */
public final class JobLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private JobLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock, or empty if the job runs already. */
    public static Optional<JobLock> tryAcquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("running.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new JobLock(channel, lock));
        } catch (OverlappingFileLockException e) {
            channel.close();
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

package eu.amadeos.apps.backup.sync;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves files into a trash after the freedesktop.org Trash specification, so a sync never deletes for good:
 * the home trash for files on the home disk, {@code .Trash-<uid>} at the top of any other disk. The File
 * Manager shows and restores both.
 */
public final class Trash {
    private final Path homeTrash;
    private final Path home;
    private final String uid;

    public Trash(Path homeTrash, Path home, String uid) {
        this.homeTrash = homeTrash;
        this.home = home;
        this.uid = uid;
    }

    /** For the running user: {@code $XDG_DATA_HOME/Trash} and the user id of the home folder's owner. */
    public static Trash forUser(Path home, Path dataHome) {
        String uid;
        try {
            uid = String.valueOf(Files.getAttribute(home, "unix:uid"));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            uid = System.getProperty("user.name", "user");
        }
        return new Trash(dataHome.resolve("Trash"), home, uid);
    }

    /** The trash folder for files at {@code path}. */
    Path trashFor(Path path) throws IOException {
        Path root = FileSystemInfo.mountRoot(path);
        if (Files.exists(home) && Files.getFileStore(home).equals(Files.getFileStore(root))) return homeTrash;
        return root.resolve(".Trash-" + uid);
    }

    /** True if {@code path} is inside the trash of its disk; syncs leave those alone. */
    public boolean isTrash(Path path) {
        try {
            Path trash = trashFor(path);
            return path.toAbsolutePath().normalize().startsWith(trash);
        } catch (IOException e) {
            return false;
        }
    }

    /** Moves the file or folder into its trash; returns where it went. */
    public Path moveToTrash(Path path) throws IOException {
        Path source = path.toAbsolutePath().normalize();
        Path trash = trashFor(source);
        Path files = trash.resolve("files");
        Path info = trash.resolve("info");
        if (!Files.isDirectory(trash)) {
            Files.createDirectories(trash);
            try {
                Files.setPosixFilePermissions(trash, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException | IOException e) {
                // FAT has no permissions
            }
        }
        Files.createDirectories(files);
        Files.createDirectories(info);
        String name = source.getFileName().toString();
        String date = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Path original = trash.equals(homeTrash) ? source : trash.getParent().relativize(source);
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? name : name + "." + n;
            Path infoFile = info.resolve(candidate + ".trashinfo");
            try {
                // Creating the info file first reserves the name, as the specification asks.
                Files.writeString(infoFile, "[Trash Info]\nPath=" + encode(original.toString()) + "\nDeletionDate=" + date
                        + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            Path target = files.resolve(candidate);
            try {
                Files.move(source, target);
            } catch (IOException e) {
                Files.deleteIfExists(infoFile);
                throw e;
            }
            return target;
        }
    }

    /** Each part of the path URL-encoded, the slashes kept. */
    static String encode(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/", -1)) parts.add(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        return String.join("/", parts);
    }
}

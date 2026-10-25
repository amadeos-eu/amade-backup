package eu.amadeos.apps.backup.disk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The disks and sticks that are plugged in and mounted, from {@code /proc/self/mounts}: everything mounted
 * below {@code /media}, {@code /run/media} or {@code /mnt}. The UUID comes from {@code /dev/disk/by-uuid},
 * so a job finds its disk again wherever it is mounted next time.
 */
public class Disks {
    private static final List<String> REMOVABLE_ROOTS = List.of("/media/", "/run/media/", "/mnt/");

    private final Path mounts;
    private final Path byUuid;
    private final Path byLabel;

    public Disks(Path mounts, Path byUuid, Path byLabel) {
        this.mounts = mounts;
        this.byUuid = byUuid;
        this.byLabel = byLabel;
    }

    public static Disks system() {
        return new Disks(Path.of("/proc/self/mounts"), Path.of("/dev/disk/by-uuid"), Path.of("/dev/disk/by-label"));
    }

    public List<Disk> mounted() {
        List<Disk> disks = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(mounts, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return disks;
        }
        Map<String, String> uuids = links(byUuid);
        Map<String, String> labels = links(byLabel);
        for (String line : lines) {
            String[] f = line.split(" ");
            if (f.length < 3) continue;
            String device = unescape(f[0]);
            String mountPoint = unescape(f[1]);
            if (REMOVABLE_ROOTS.stream().noneMatch(mountPoint::startsWith)) continue;
            Path mp = Path.of(mountPoint);
            String real = realDevice(device);
            String uuid = uuids.getOrDefault(real, "");
            String label = labels.getOrDefault(real, mp.getFileName() == null ? mountPoint : mp.getFileName().toString());
            long usable = 0, total = 0;
            try {
                FileStore store = Files.getFileStore(mp);
                usable = store.getUsableSpace();
                total = store.getTotalSpace();
            } catch (IOException e) {
                // shown without sizes
            }
            disks.add(new Disk(label, mp, uuid, f[2], usable, total));
        }
        return disks;
    }

    public Optional<Disk> byUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return Optional.empty();
        return mounted().stream().filter(d -> d.uuid().equalsIgnoreCase(uuid)).findFirst();
    }

    /** The disk a path is on, if it is one of the mounted ones. */
    public Optional<Disk> containing(Path path) {
        Path p = path.toAbsolutePath().normalize();
        return mounted().stream().filter(d -> p.startsWith(d.mountPoint()))
                .max((a, b) -> Integer.compare(a.mountPoint().getNameCount(), b.mountPoint().getNameCount()));
    }

    /** The device a {@code /dev/disk/by-*} link points to, by its real path, to the link's name (UUID or label). */
    private static Map<String, String> links(Path dir) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isDirectory(dir)) return map;
        try (DirectoryStream<Path> links = Files.newDirectoryStream(dir)) {
            for (Path link : links) {
                try {
                    map.put(link.toRealPath().toString(), unescape(link.getFileName().toString()));
                } catch (IOException e) {
                    // a stale link
                }
            }
        } catch (IOException e) {
            // no links
        }
        return map;
    }

    private static String realDevice(String device) {
        try {
            return Path.of(device).toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            return device;
        }
    }

    /** {@code /proc/mounts} writes spaces as {@code \040}, udev links write them as {@code \x20}. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.charAt(i + 1) == 'x') {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // literal
                }
            }
            if (c == '\\' && i + 3 < s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}

package eu.amadeos.apps.backup.domain;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Which {@link HomeArea} a path in the home folder belongs to, after the user's folders in
 * {@code ~/.config/user-dirs.dirs} (so a German "Bilder" counts as Pictures). Caches and the trash belong to
 * no area: they never go into a backup.
 */
public final class HomeLayout {
    /** Never backed up: they are rebuilt or thrown away anyway. */
    public static final List<String> ALWAYS_LEFT_OUT = List.of(".cache", ".thumbnails", ".local/share/Trash");

    private static final String PERSONAL = ".local/share/amadeos";

    private final Path home;
    private final Map<String, HomeArea> topLevel = new TreeMap<>();

    public HomeLayout(Path home) {
        this.home = home.toAbsolutePath().normalize();
        Map<String, String> dirs = UserDirs.read(this.home);
        put(dirs.getOrDefault("DOCUMENTS", "Documents"), HomeArea.DOCUMENTS);
        put(dirs.getOrDefault("PICTURES", "Pictures"), HomeArea.PICTURES);
        put(dirs.getOrDefault("MUSIC", "Music"), HomeArea.MUSIC);
        put(dirs.getOrDefault("VIDEOS", "Videos"), HomeArea.VIDEOS);
        put(dirs.getOrDefault("DOWNLOAD", "Downloads"), HomeArea.DOWNLOADS);
        put(dirs.getOrDefault("DESKTOP", "Desktop"), HomeArea.DESKTOP);
        put(dirs.getOrDefault("TEMPLATES", "Templates"), HomeArea.DESKTOP);
        put(dirs.getOrDefault("PUBLICSHARE", "Public"), HomeArea.DESKTOP);
    }

    private void put(String relative, HomeArea area) {
        // A user folder that is the home folder itself, or nested, is not an area of its own.
        if (!relative.isEmpty() && !relative.contains("/")) topLevel.putIfAbsent(relative, area);
    }

    public Path home() {
        return home;
    }

    /** The area of a path relative to the home folder; empty for caches and the trash. */
    // --8<-- [start:area]
    public Optional<HomeArea> areaOf(Path relative) {
        String path = relative.normalize().toString().replace('\\', '/');
        for (String out : ALWAYS_LEFT_OUT) {
            if (path.equals(out) || path.startsWith(out + "/")) return Optional.empty();
        }
        if (path.equals(PERSONAL) || path.startsWith(PERSONAL + "/")) return Optional.of(HomeArea.PERSONAL_DATA);
        String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
        HomeArea area = topLevel.get(first);
        if (area != null) return Optional.of(area);
        return Optional.of(first.startsWith(".") ? HomeArea.SETTINGS : HomeArea.OTHER);
    }
    // --8<-- [end:area]

    /**
     * The absolute paths to leave out so that only {@code included} is backed up: whole folders where possible,
     * the children of {@code .local} and {@code .local/share} where those mix areas. Read at every run, so new
     * folders follow their area.
     */
    public List<Path> excludes(Set<HomeArea> included) throws IOException {
        List<Path> out = new ArrayList<>();
        collect(Path.of(""), included, out);
        return out;
    }

    private void collect(Path relative, Set<HomeArea> included, List<Path> out) throws IOException {
        Path dir = home.resolve(relative);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                Path rel = relative.resolve(child.getFileName());
                String name = rel.toString().replace('\\', '/');
                if (name.equals(".local") || name.equals(".local/share")) {
                    collect(rel, included, out);
                    continue;
                }
                Optional<HomeArea> area = areaOf(rel);
                if (area.isEmpty() || !included.contains(area.get())) out.add(home.resolve(rel));
            }
        }
        out.sort(null);
    }

    /** Bytes and files of each area; caches and the trash under {@code null}. */
    public record Sizes(Map<HomeArea, Long> bytes, Map<HomeArea, Long> files, long leftOutBytes) {
        public long bytes(Set<HomeArea> areas) {
            long sum = 0;
            for (HomeArea area : areas) sum += bytes.getOrDefault(area, 0L);
            return sum;
        }

        public long files(Set<HomeArea> areas) {
            long sum = 0;
            for (HomeArea area : areas) sum += files.getOrDefault(area, 0L);
            return sum;
        }
    }

    /** Walks the whole home folder without following links; unreadable folders are skipped. */
    public Sizes measure(BooleanSupplier cancelled) throws IOException {
        Map<HomeArea, Long> bytes = new EnumMap<>(HomeArea.class);
        Map<HomeArea, Long> files = new EnumMap<>(HomeArea.class);
        long[] leftOut = {0};
        Files.walkFileTree(home, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Optional<HomeArea> area = areaOf(home.relativize(file));
                if (area.isEmpty()) {
                    leftOut[0] += attrs.size();
                } else {
                    bytes.merge(area.get(), attrs.size(), Long::sum);
                    files.merge(area.get(), 1L, Long::sum);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return new Sizes(bytes, files, leftOut[0]);
    }
}

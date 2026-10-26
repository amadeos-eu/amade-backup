package eu.amadeos.apps.backup.sync;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Leave-out patterns for syncs, written like restic's: {@code *.iso} matches a name anywhere,
 * {@code **}{@code /build} or {@code Music/Old*} a path inside the folder pair, {@code /home/ralf/tmp} or
 * {@code ~/tmp} an absolute path. A matching folder is left out with everything in it.
 */
public final class Patterns {
    private record Rule(PathMatcher matcher, Kind kind) {
    }

    private enum Kind { NAME, RELATIVE, ABSOLUTE }

    public static final Patterns NONE = new Patterns(List.of(), Path.of("/"));

    private final List<Rule> rules = new ArrayList<>();

    public Patterns(List<String> patterns, Path home) {
        var fs = FileSystems.getDefault();
        for (String raw : patterns) {
            String p = raw.strip();
            if (p.isEmpty() || p.startsWith("#")) continue;
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (p.startsWith("~/")) p = home.resolve(p.substring(2)).toString();
            Kind kind = p.startsWith("/") ? Kind.ABSOLUTE : p.contains("/") ? Kind.RELATIVE : Kind.NAME;
            String glob = kind == Kind.RELATIVE && p.startsWith("**/") ? "{" + p.substring(3) + "," + p + "}" : p;
            rules.add(new Rule(fs.getPathMatcher("glob:" + glob), kind));
        }
    }

    /** True if the entry at {@code absolute}, which is {@code relative} inside its folder pair, is left out. */
    public boolean excludes(Path absolute, Path relative) {
        Path name = relative.getFileName();
        for (Rule rule : rules) {
            boolean hit = switch (rule.kind) {
                case NAME -> name != null && rule.matcher.matches(name);
                case RELATIVE -> rule.matcher.matches(relative);
                case ABSOLUTE -> rule.matcher.matches(absolute);
            };
            if (hit) return true;
        }
        return false;
    }
}

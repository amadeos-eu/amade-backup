package eu.amadeos.apps.backup.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The user's folders from {@code ~/.config/user-dirs.dirs} (xdg-user-dirs), relative to the home folder. */
final class UserDirs {
    private static final Pattern LINE = Pattern.compile("^XDG_([A-Z]+)_DIR=\"\\$HOME/?(.*)\"\\s*$");

    private UserDirs() {
    }

    /** "PICTURES" to "Bilder"; a folder written as an absolute path or outside the home folder is left out. */
    static Map<String, String> read(Path home) {
        Map<String, String> dirs = new HashMap<>();
        Path file = home.resolve(".config/user-dirs.dirs");
        if (!Files.isRegularFile(file)) return dirs;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = LINE.matcher(line.strip());
                if (m.matches()) dirs.put(m.group(1), m.group(2).replaceAll("/+$", ""));
            }
        } catch (IOException e) {
            // the English defaults then
        }
        return dirs;
    }
}

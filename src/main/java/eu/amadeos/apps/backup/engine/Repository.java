package eu.amadeos.apps.backup.engine;

import java.nio.file.Path;
import java.util.Objects;

/** A backup repository and the password that opens it. */
public record Repository(Path location, String password) {
    public Repository {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(password, "password");
    }

    @Override
    public String toString() {
        return "Repository[" + location + "]";
    }
}

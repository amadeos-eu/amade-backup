package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordsTest {
    @TempDir
    Path dir;

    @Test
    void generatedPasswordsAreLongAndEasyToCopyByHand() {
        Passwords passwords = new Passwords(new SessionPasswordStore());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String p = passwords.generate();
            assertTrue(p.matches("[a-km-z2-9]{5}(-[a-km-z2-9]{5}){5}"), p);
            seen.add(p);
        }
        assertEquals(50, seen.size());
    }

    @Test
    void passwordsAreKeptPerJob() {
        SessionPasswordStore store = new SessionPasswordStore();
        Passwords passwords = new Passwords(store);
        Job job = Job.backup("docs", "Docs", List.of(dir), Target.folder(dir.resolve("repo")));
        passwords.store(job, "secret words");
        assertEquals(Optional.of("secret words"), store.lookup("job:docs"));
        assertEquals(Optional.of("secret words"), passwords.lookup(job));
        passwords.delete(job);
        assertEquals(Optional.empty(), passwords.lookup(job));
    }
}

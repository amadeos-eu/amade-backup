package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.commons.desktop.PasswordStore;

import java.security.SecureRandom;
import java.util.Optional;

/** The repository password of each backup job, in the login keyring. */
public class Passwords {
    /** No 0/O, 1/l/I, so a printed password can be typed in again without guessing. */
    static final String ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789";

    private final PasswordStore store;
    private final SecureRandom random = new SecureRandom();

    public Passwords(PasswordStore store) {
        this.store = store;
    }

    public Optional<String> lookup(Job job) {
        return store.lookup(key(job.id())).filter(p -> !p.isEmpty());
    }

    public void store(Job job, String password) {
        store.store(key(job.id()), "amadeOS backup " + job.name(), password);
    }

    public void delete(Job job) {
        store.delete(key(job.id()));
    }

    /** False if the keyring is not available and passwords live only while the program runs. */
    public boolean persistent() {
        return store.isPersistent();
    }

    /** Six groups of five letters and digits, about 150 bits: "k7mqa-3xv9p-...". */
    // --8<-- [start:generate]
    public String generate() {
        StringBuilder sb = new StringBuilder();
        for (int group = 0; group < 6; group++) {
            if (group > 0) sb.append('-');
            for (int i = 0; i < 5; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
    // --8<-- [end:generate]

    static String key(String jobId) {
        return "job:" + jobId;
    }
}

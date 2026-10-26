package eu.amadeos.apps.backup.run;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.commons.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code README-amadeOS-backup.txt} next to the repositories on a backup disk: what the folders are and how to
 * get the files back with restic on any computer, without amadeOS. In the user's language, and in English too.
 */
public class Readme {
    public static final String FILE = "README-amadeOS-backup.txt";
    private static final Logger LOG = LoggerFactory.getLogger(Readme.class);

    private final Messages msg;
    private final Messages english;

    public Readme(Messages msg, Messages english) {
        this.msg = msg;
        this.english = english;
    }

    public void write(Job job, Path repository) {
        Path file = repository.getParent().resolve(FILE);
        if (Files.exists(file)) return;
        String text = text(msg, repository);
        if (!msg.locale().getLanguage().equals(Locale.ENGLISH.getLanguage())) {
            text += "\n\n----------------------------------------------------------------------\n\n" + text(english, repository);
        }
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Cannot write {}", file, e);
        }
    }

    public static String text(Messages msg, Path repository) {
        String folder = repository.getParent().getFileName().toString();
        return msg.format("readme.text", folder, repository.getFileName().toString());
    }
}

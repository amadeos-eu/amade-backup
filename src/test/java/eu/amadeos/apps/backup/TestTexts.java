package eu.amadeos.apps.backup;

import eu.amadeos.apps.backup.bootstrap.BackupModule;
import eu.amadeos.commons.i18n.Messages;

import java.util.Locale;

/** The program's texts for tests, which compare English and German sentences. */
public final class TestTexts {
    public static final Messages EN = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.UK);
    public static final Messages DE = Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.GERMANY);

    private TestTexts() {
    }
}

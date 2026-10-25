package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.apps.backup.ui.common.PasswordDialog;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** What the presenter needs from the window; tests answer with a fake. */
public interface MainView {
    /** A job in the list: its state light and the line below its name. */
    record JobRow(Job job, State state, String subtitle) {
    }

    enum State { OK, WARNING, PROBLEM, IDLE }

    /** The selected job: headline with state, a line of facts, the settings rows (label, value). */
    record Detail(Job job, State state, String headline, String subline, List<String[]> facts) {
    }

    interface Progress {
        void update(BackupProgress progress);

        void close();
    }

    void showJobs(List<JobRow> rows, int selected);

    void showDetail(Detail detail);

    void showHistory(Job job, List<RunRecord> runs);

    void showStatus(String text);

    void setBusy(boolean busy);

    Optional<HomeAssistant.Result> homeAssistant();

    Optional<Job> editJob(Job job, boolean isNew);

    /** Asks for the password of a new backup; empty if cancelled. */
    Optional<String> newPassword(Job job);

    Optional<PasswordDialog.Answer> askPassword(Job job, String problem);

    /** A folder that holds a backup, for Open Existing Backup. */
    Optional<Path> chooseBackupFolder();

    /** Shows the preview; true if the user starts the sync. */
    boolean confirmSync(Job job, List<SyncPlan> plans);

    void openRestore(Job job, Repository repository);

    Progress progress(String title, String heading, Cancellation cancellation);

    void printRecoverySheet(Job job, String where, String password);

    boolean confirm(String title, String question);

    void showInfo(String text);

    void showError(String text);

    void showLog(String title, String text);

    void showAbout(String engineVersion);

    void close();
}

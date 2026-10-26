package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.sync.SyncPlan;

import java.util.List;
import java.util.function.Consumer;

/**
 * What the helper tells the user: small windows in the desktop, or a recorder in tests. Every call returns at
 * once, so the helper goes on with the other jobs while a window waits for an answer.
 */
public interface Notices {
    /** A backup is overdue and its disk is not connected. */
    void remind(Job job, String text);

    /** A run or a check went wrong. */
    void problem(Job job, String text);

    /**
     * A sync would remove or replace files: the preview, as when the sync is started by hand; {@code answer}
     * gets true for Sync Now, false when the preview is closed.
     */
    void offerSync(Job job, List<SyncPlan> plans, Consumer<Boolean> answer);
}

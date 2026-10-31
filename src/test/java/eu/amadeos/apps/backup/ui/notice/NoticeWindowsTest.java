package eu.amadeos.apps.backup.ui.notice;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import java.awt.GraphicsEnvironment;
import java.time.ZoneOffset;

import static eu.amadeos.apps.backup.Components.find;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class NoticeWindowsTest {
    @Test
    void aReminderOpensTheWindowOrWaits() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        int[] opened = {0};
        NoticeWindows notices = new NoticeWindows(EN, new Formats(EN, ZoneOffset.UTC), () -> opened[0]++);
        Job job = Job.backup("docs", "Documents", java.util.List.of(java.nio.file.Path.of("/home/ralf/Documents")),
                Target.onDisk("1234-ABCD", "BACKUP", java.nio.file.Path.of("amadeos-backup/docs")));
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            JDialog first = notices.show(job, EN.get("agent.remind.title"), "Connect the disk BACKUP", "agent.later");
            assertEquals("Later", find(first.getRootPane(),
                    "notice.dismiss", JButton.class).getText());
            JButton open = find(first.getRootPane(), "notice.open", JButton.class);
            assertEquals("Open Backup & Sync", open.getText());
            assertTrue(find(first.getRootPane(), "notice.text", JLabel.class).getText().contains("BACKUP"));

            // A second notice for the same job replaces the first
            JDialog second = notices.show(job, EN.get("agent.problem.title"), "It failed", "agent.close");
            assertFalse(first.isDisplayable());
            find(second.getRootPane(), "notice.open", JButton.class).doClick();
            assertEquals(1, opened[0]);
            assertFalse(second.isDisplayable());
        });
    }

    @Test
    void remindersAndProblemsBecomeDesktopNotificationsThatReplaceEachOther() {
        int[] opened = {0};
        java.util.List<eu.amadeos.commons.desktop.Notification> sent = new java.util.ArrayList<>();
        NoticeWindows notices = new NoticeWindows(EN, new Formats(EN, ZoneOffset.UTC), () -> opened[0]++, n -> {
            sent.add(n);
            return 40 + sent.size();
        });
        Job job = Job.backup("docs", "Documents", java.util.List.of(java.nio.file.Path.of("/home/ralf/Documents")),
                Target.onDisk("1234-ABCD", "BACKUP", java.nio.file.Path.of("amadeos-backup/docs")));
        notices.remind(job, "Connect the disk <BACKUP>");
        assertEquals(EN.get("agent.remind.title"), sent.get(0).summary());
        assertEquals("Connect the disk &lt;BACKUP&gt;", sent.get(0).body());
        assertEquals("Open Backup & Sync", sent.get(0).actions().get(0).label());
        sent.get(0).actions().get(0).run().run();
        sent.get(0).defaultAction().run();
        assertEquals(2, opened[0]);

        notices.problem(job, "It failed");
        assertEquals(41, sent.get(1).replaces(), "replaces the reminder of the same job");
        assertEquals(eu.amadeos.commons.desktop.Notification.Urgency.CRITICAL, sent.get(1).urgency());
    }
}

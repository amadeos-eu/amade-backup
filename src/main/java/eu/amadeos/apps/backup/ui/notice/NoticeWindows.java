package eu.amadeos.apps.backup.ui.notice;

import eu.amadeos.apps.backup.agent.Notices;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.main.AppIcon;
import eu.amadeos.apps.backup.ui.sync.SyncPreviewDialog;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;

/**
 * The helper's small windows: a reminder or a problem with <i>Open Backup &amp; Sync</i> and <i>Later</i> or
 * <i>Close</i>, in the lower right corner above the Front Panel, and the sync preview. A notice for a job
 * that already has one open replaces it.
 */
public final class NoticeWindows implements Notices {
    private final Messages msg;
    private final Formats formats;
    private final Runnable openApp;
    private final java.util.Map<String, JDialog> open = new java.util.HashMap<>();

    /** @param openApp starts the Backup &amp; Sync window */
    public NoticeWindows(Messages msg, Formats formats, Runnable openApp) {
        this.msg = msg;
        this.formats = formats;
        this.openApp = openApp;
    }

    @Override
    public void remind(Job job, String text) {
        SwingUtilities.invokeLater(() -> show(job, msg.get("agent.remind.title"), text, "agent.later"));
    }

    @Override
    public void problem(Job job, String text) {
        SwingUtilities.invokeLater(() -> show(job, msg.get("agent.problem.title"), text, "agent.close"));
    }

    @Override
    public void offerSync(Job job, List<SyncPlan> plans, Consumer<Boolean> answer) {
        SwingUtilities.invokeLater(() -> {
            SyncPreviewDialog preview = new SyncPreviewDialog(null, msg, formats, job, plans);
            preview.setIconImage(AppIcon.image());
            answer.accept(preview.showAndWait());
        });
    }

    /** The notice window; on the event dispatch thread. */
    public JDialog show(Job job, String title, String text, String dismissKey) {
        JDialog previous = open.remove(job.id());
        if (previous != null) previous.dispose();

        JDialog dialog = new JDialog((java.awt.Frame) null, title, false);
        dialog.setName("notice");
        dialog.setIconImage(AppIcon.image());
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel icon = new JLabel(AppIcon.icon(Math.round(AmadeUI.scaled(48))));
        icon.setVerticalAlignment(SwingConstants.TOP);
        int width = Math.round(AmadeUI.scaled(340));
        JLabel message = new JLabel("<html><div style='width:" + width + "px'>" + escape(text).replace("\n", "<br>")
                + "</div></html>");
        message.setName("notice.text");

        JButton openButton = Mnemonics.apply(new JButton(), msg.get("agent.open"));
        openButton.setName("notice.open");
        openButton.addActionListener(e -> {
            dialog.dispose();
            openApp.run();
        });
        JButton dismiss = Mnemonics.apply(new JButton(), msg.get(dismissKey));
        dismiss.setName("notice.dismiss");
        dismiss.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(openButton);
        buttons.add(dismiss);

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(icon, BorderLayout.WEST);
        content.add(message, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(openButton);
        dialog.pack();
        place(dialog);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                open.remove(job.id(), dialog);
            }
        });
        open.put(job.id(), dialog);
        dialog.setVisible(true);
        return dialog;
    }

    /** Lower right, above the Front Panel at the bottom of the screen. */
    private static void place(JDialog dialog) {
        if (GraphicsEnvironment.isHeadless()) return;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension size = dialog.getSize();
        int margin = Math.round(AmadeUI.scaled(16));
        int panel = Math.round(AmadeUI.scaled(110));
        dialog.setLocation(screen.x + screen.width - size.width - margin,
                screen.y + screen.height - size.height - margin - panel);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

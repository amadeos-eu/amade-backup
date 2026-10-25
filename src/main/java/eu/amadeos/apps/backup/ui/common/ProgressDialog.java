package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.apps.backup.domain.BackupProgress;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Shows a running backup, restore or sync with a progress bar and a Stop button; the window stays usable. */
public final class ProgressDialog extends JDialog {
    private final Messages msg;
    private final Formats formats;
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel counts = new JLabel(" ");
    private final JLabel file = new JLabel(" ");
    private final JButton stop;

    public ProgressDialog(Window owner, Formats formats, String title, String heading, Cancellation cancellation) {
        super(owner, title, ModalityType.MODELESS);
        EdtExecutor.requireEdt();
        this.formats = formats;
        this.msg = formats.messages();
        setName("progress");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        JLabel head = new JLabel(heading);
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        content.add(head, BorderLayout.NORTH);
        JPanel middle = new JPanel(new GridLayout(3, 1, 0, 4));
        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        middle.add(bar);
        middle.add(counts);
        middle.add(file);
        content.add(middle, BorderLayout.CENTER);
        stop = new JButton(msg.get("progress.stop"));
        stop.setName("progress.stop");
        stop.addActionListener(e -> {
            stop.setEnabled(false);
            counts.setText(msg.get("progress.stopping"));
            Thread.ofVirtual().start(cancellation::cancel);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(stop);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stop.doClick();
            }
        });
        setPreferredSize(new Dimension(520, 190));
        pack();
        setLocationRelativeTo(owner);
    }

    /** From any thread. */
    public void update(BackupProgress progress) {
        EdtExecutor edt = new EdtExecutor();
        edt.execute(() -> show(progress));
    }

    void show(BackupProgress p) {
        if (!stop.isEnabled()) return;
        if (p.totalBytes() > 0 || p.totalFiles() > 0) {
            bar.setIndeterminate(false);
            bar.setValue((int) Math.round(p.fraction() * 1000));
            counts.setText(msg.format("progress.counts", formats.count(p.filesDone()), formats.count(p.totalFiles()),
                    formats.bytes(p.bytesDone()), formats.bytes(p.totalBytes())));
        }
        file.setText(p.currentFiles().isEmpty() ? " " : elide(p.currentFiles().getFirst()));
    }

    private static String elide(String path) {
        return path.length() <= 70 ? path : "…" + path.substring(path.length() - 69);
    }

    public void close() {
        setVisible(false);
        dispose();
    }
}

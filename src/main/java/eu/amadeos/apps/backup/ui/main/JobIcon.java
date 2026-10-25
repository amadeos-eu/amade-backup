package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.commons.swing.AmadeUI;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** The symbol of a job's target (disk, folder, or the two arrows of a sync) with its state light. */
final class JobIcon implements Icon {
    private final String kind;
    private final Color state;

    private JobIcon(String kind, Color state) {
        this.kind = kind;
        this.state = state;
    }

    static JobIcon of(MainView.JobRow row) {
        String kind = row.job().kind() == JobKind.SYNC ? "sync"
                : row.job().target() != null && row.job().target().onRemovableDisk() ? "disk" : "folder";
        Color color = switch (row.state()) {
            case OK -> MainWindow.OK;
            case WARNING -> MainWindow.WARNING;
            case PROBLEM -> MainWindow.PROBLEM;
            case IDLE -> AmadeUI.theme().textInactive();
        };
        return new JobIcon(kind, color);
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.setStroke(new BasicStroke(1.4f));
        switch (kind) {
            case "disk" -> {
                g.setColor(new Color(0x8A8F98));
                g.fillRoundRect(2, 8, 28, 16, 5, 5);
                g.setColor(Color.DARK_GRAY);
                g.drawRoundRect(2, 8, 28, 16, 5, 5);
                g.setColor(new Color(0x66DD77));
                g.fillOval(23, 18, 4, 4);
            }
            case "folder" -> {
                g.setColor(new Color(0xD9B45A));
                g.fillRect(3, 9, 12, 4);
                g.fillRect(3, 11, 26, 13);
                g.setColor(Color.DARK_GRAY);
                g.drawRect(3, 11, 26, 13);
            }
            default -> {
                g.setColor(new Color(0x5B7FA6));
                g.setStroke(new BasicStroke(2.2f));
                g.drawLine(6, 12, 24, 12);
                g.drawLine(19, 7, 24, 12);
                g.drawLine(19, 17, 24, 12);
                g.drawLine(8, 20, 26, 20);
                g.drawLine(8, 20, 13, 15);
                g.drawLine(8, 20, 13, 25);
            }
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(state);
        g.fillOval(0, 0, 11, 11);
        g.setColor(Color.WHITE);
        g.drawOval(0, 0, 11, 11);
        g.dispose();
    }

    @Override
    public int getIconWidth() {
        return 32;
    }

    @Override
    public int getIconHeight() {
        return 26;
    }
}

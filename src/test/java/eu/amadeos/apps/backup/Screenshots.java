package eu.amadeos.apps.backup;

import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.domain.HomeLayout;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.main.MainPresenter;
import eu.amadeos.apps.backup.ui.main.MainWindow;
import eu.amadeos.apps.backup.ui.notice.NoticeWindows;
import eu.amadeos.apps.backup.ui.restore.RestoreWindow;
import eu.amadeos.apps.backup.ui.sync.SyncPreviewDialog;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.theme.DesktopTheme;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.RootPaneContainer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static eu.amadeos.apps.backup.Components.await;
import static eu.amadeos.apps.backup.Components.find;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;

/**
 * The pictures of the README and the AppStream metadata, taken from the real windows on the {@link Scene}:
 * {@code xvfb-run ./gradlew screenshots}. The window frames are drawn like the amadeOS window manager's.
 */
public final class Screenshots {
    private static DesktopTheme theme = DesktopTheme.WARM_MOTIF;

    public static void main(String[] args) throws Exception {
        try {
            take(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        } finally {
            System.exit(0);
        }
    }

    private static void take(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/screenshots");
        Path work = Files.createTempDirectory("amade-backup-shots");
        Path home = args.length > 1 ? Path.of(args[1]) : work.resolve("home");
        Path disk = args.length > 2 ? Path.of(args[2]) : work.resolve("media/BACKUP");
        Files.createDirectories(out);
        Scene scene = new Scene(work, home, disk, EN);
        ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
        onEdt(() -> AmadeUI.install(theme));

        MainWindow[] main = new MainWindow[1];
        onEdt(() -> {
            main[0] = new MainWindow(EN, null, scene.disks, scene.places, scene.passwords, scene.engine, scene.restorer,
                    new DesktopLauncher(), background, scene.xdg, scene.clock, scene.jobs);
            MainPresenter presenter = new MainPresenter(main[0], EN, scene.jobs, scene.history, scene.places,
                    scene.passwords, scene.backups, scene.syncs, null, background, new EdtExecutor(), scene.clock,
                    scene.home, () -> { }, null);
            main[0].bind(presenter);
            main[0].setSize(1000, 660);
            main[0].setVisible(true);
            presenter.start();
        });
        await(() -> find(main[0], "detail.subline", JLabel.class).getText().contains("backups"));
        save(main[0], out.resolve("amade-backup.png"));

        RestoreWindow[] restore = new RestoreWindow[1];
        onEdt(() -> {
            restore[0] = new RestoreWindow(EN, new Formats(EN, Scene.ZONE), scene.homeJob, scene.repository, scene.engine,
                    scene.restorer, new DesktopLauncher(), background, scene.clock, scene.xdg);
            restore[0].setSize(1100, 640);
            restore[0].setVisible(true);
            restore[0].load();
        });
        JTree tree = find(restore[0], "restore.tree", JTree.class);
        await(() -> {
            for (int r = 0; r < tree.getRowCount(); r++) {
                if (tree.getPathForRow(r).getLastPathComponent().toString().equals("Documents")) return true;
            }
            return false;
        });
        onEdt(() -> {
            for (int r = 0; r < tree.getRowCount(); r++) {
                TreePath p = tree.getPathForRow(r);
                if (p.getLastPathComponent().toString().equals("Documents")) {
                    tree.setSelectionPath(p);
                    tree.expandPath(p);
                    break;
                }
            }
        });
        JTable files = find(restore[0], "restore.files", JTable.class);
        await(() -> files.getRowCount() == 4);
        onEdt(() -> {
            for (int r = 0; r < files.getRowCount(); r++) {
                if (files.getValueAt(r, 0).equals("report.odt")) files.setRowSelectionInterval(r, r);
            }
        });
        JTable versions = find(restore[0], "restore.versions", JTable.class);
        await(() -> versions.getRowCount() >= 3);
        save(restore[0], out.resolve("amade-backup-restore.png"));
        onEdt(restore[0]::dispose);

        Files.createDirectories(scene.stick.resolve("Music/Old"));
        Files.writeString(scene.stick.resolve("Music/Old/demo.mp3"), "x".repeat(3_400_000));
        List<SyncPlan> plans = scene.syncs.plan(scene.syncJob, Cancellation.none());
        SyncPreviewDialog[] preview = new SyncPreviewDialog[1];
        onEdt(() -> {
            preview[0] = new SyncPreviewDialog(main[0], EN, new Formats(EN, Scene.ZONE), scene.syncJob, plans);
            preview[0].setModalityType(JDialog.ModalityType.MODELESS);
            preview[0].setVisible(true);
        });
        Thread.sleep(500);
        save(preview[0], out.resolve("amade-backup-sync.png"));
        onEdt(preview[0]::dispose);

        HomeAssistant[] assistant = new HomeAssistant[1];
        onEdt(() -> {
            assistant[0] = new HomeAssistant(main[0], EN, new Formats(EN, Scene.ZONE), new HomeLayout(scene.home),
                    scene.disks, scene.places, scene.passwords, scene.jobs.load(), background, scene.clock);
            assistant[0].setModalityType(JDialog.ModalityType.MODELESS);
            assistant[0].setVisible(true);
            find(assistant[0], "assistant.next", JButton.class).doClick();
        });
        await(() -> find(assistant[0], "assistant.what.sum", JLabel.class).getText().contains("files"));
        save(assistant[0], out.resolve("amade-backup-assistant.png"));
        onEdt(assistant[0]::dispose);

        // The helper's reminder, as it comes when the disk has been away for nine days
        JDialog[] notice = new JDialog[1];
        onEdt(() -> {
            Formats formats = new Formats(EN, Scene.ZONE);
            java.time.Instant now = scene.clock.instant();
            java.time.Instant last = now.minus(java.time.Duration.ofDays(9));
            String text = EN.format("agent.remind.disk", scene.homeJob.name(), formats.when(last, now), 9,
                    scene.homeJob.target().diskLabel());
            notice[0] = new NoticeWindows(EN, formats, () -> { }).show(scene.homeJob, EN.get("agent.remind.title"), text,
                    "agent.later");
        });
        Thread.sleep(300);
        save(notice[0], out.resolve("amade-backup-reminder.png"));
        onEdt(notice[0]::dispose);

        onEdt(main[0]::dispose);
        background.shutdownNow();
        System.exit(0);
    }

    /** The window's inside with a drawn frame, on the workspace colour. */
    static void save(Window window, Path file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        onEdt(() -> {
            String title = window instanceof JFrame f ? f.getTitle() : ((JDialog) window).getTitle();
            img[0] = framed(title, (RootPaneContainer) window);
        });
        int gap = 20;
        BufferedImage shot = new BufferedImage(img[0].getWidth() + 2 * gap, img[0].getHeight() + 2 * gap,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = shot.createGraphics();
        g.setColor(theme.workspaceColor(0));
        g.fillRect(0, 0, shot.getWidth(), shot.getHeight());
        g.drawImage(img[0], gap, gap, null);
        g.dispose();
        ImageIO.write(shot, "png", file.toFile());
        System.out.println("Wrote " + file);
    }

    private static BufferedImage framed(String title, RootPaneContainer window) {
        var root = window.getRootPane();
        int cw = root.getWidth(), ch = root.getHeight();
        int border = 5, titleH = 24;
        int w = cw + 2 * border, h = ch + 2 * border + titleH;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(theme.activeTitleBg());
        g.fillRect(0, 0, w, h);
        AmadeUI.paintBevel(g, 0, 0, w, h, true, 2);
        int x0 = border, y0 = border;
        AmadeUI.paintBevel(g, x0, y0, titleH, titleH, true, 1);
        g.setColor(theme.highlight());
        g.fillRect(x0 + 6, y0 + 10, titleH - 12, 4);
        AmadeUI.paintBevel(g, x0 + 6, y0 + 10, titleH - 12, 4, true, 1);
        int tx = x0 + titleH, tw = w - 2 * border - 3 * titleH;
        AmadeUI.paintBevel(g, tx, y0, tw, titleH, true, 1);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.setColor(DesktopTheme.isDark(theme.activeTitleBg()) ? Color.WHITE : Color.BLACK);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, tx + (tw - fm.stringWidth(title)) / 2, y0 + (titleH + fm.getAscent()) / 2 - 2);
        int bx = tx + tw;
        AmadeUI.paintBevel(g, bx, y0, titleH, titleH, true, 1);
        g.setColor(theme.highlight());
        g.fillRect(bx + 10, y0 + 10, 4, 4);
        AmadeUI.paintBevel(g, bx + 10, y0 + 10, 4, 4, true, 1);
        bx += titleH;
        AmadeUI.paintBevel(g, bx, y0, titleH, titleH, true, 1);
        AmadeUI.paintBevel(g, bx + 5, y0 + 5, titleH - 10, titleH - 10, true, 1);
        Graphics2D cg = (Graphics2D) g.create(border, border + titleH, cw, ch);
        root.printAll(cg);
        cg.dispose();
        g.dispose();
        return img;
    }
}

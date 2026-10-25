package eu.amadeos.apps.backup.ui.restore;

import eu.amadeos.apps.backup.Scene;
import eu.amadeos.apps.backup.domain.Timeline;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static eu.amadeos.apps.backup.Components.await;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Browsing the scene's five backups. Needs a display. */
class RestoreWindowTest {
    @TempDir
    Path dir;

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private RestoreWindow window;

    static RestoreWindow open(Scene scene, ExecutorService background) throws Exception {
        RestoreWindow[] w = new RestoreWindow[1];
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            w[0] = new RestoreWindow(EN, new Formats(EN, Scene.ZONE), scene.homeJob, scene.repository, scene.engine,
                    scene.restorer, new DesktopLauncher(), background, scene.clock, scene.xdg);
            w[0].setVisible(true);
            w[0].load();
        });
        return w[0];
    }

    @AfterEach
    void close() throws Exception {
        if (window != null) onEdt(window::dispose);
        background.shutdownNow();
    }

    @Test
    void theTimelineGroupsTheBackupsAndTheNewestIsShown() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Scene scene = new Scene(dir, dir.resolve("home"), dir.resolve("media/BACKUP"), EN);
        window = open(scene, background);
        await(() -> window.timelineList().getModel().getSize() == 10);
        onEdt(() -> {
            var model = window.timelineList().getModel();
            assertEquals("TODAY", window.heading(assertInstanceOf(Timeline.Heading.class, model.getElementAt(0))));
            assertEquals("YESTERDAY", window.heading((Timeline.Heading) model.getElementAt(2)));
            assertEquals("THIS WEEK", window.heading((Timeline.Heading) model.getElementAt(4)));
            assertEquals("OCTOBER", window.heading((Timeline.Heading) model.getElementAt(6)));
            assertEquals("SEPTEMBER", window.heading((Timeline.Heading) model.getElementAt(8)));
            assertEquals(1, window.timelineList().getSelectedIndex());
        });
        // open the Documents folder of the newest backup
        Path docs = scene.home.resolve("Documents");
        onEdt(() -> window.showFolder(docs.toString()));
        await(() -> window.fileTable().getRowCount() == 4);
        onEdt(() -> {
            var t = window.fileTable();
            assertEquals("Letters/", t.getValueAt(0, 0));
            assertEquals("garden plan.ods", t.getValueAt(1, 0));
            assertEquals("changed", t.getValueAt(rowOf(t, "report.odt"), 3));
            assertEquals("same", t.getValueAt(rowOf(t, "tax return 2025.pdf"), 3));
        });
    }

    private static int rowOf(javax.swing.JTable t, String name) {
        for (int r = 0; r < t.getRowCount(); r++) if (name.equals(t.getValueAt(r, 0))) return r;
        throw new AssertionError(name + " not listed");
    }
}

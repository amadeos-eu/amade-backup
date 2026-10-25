package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.apps.backup.Scene;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static eu.amadeos.apps.backup.Components.await;
import static eu.amadeos.apps.backup.Components.find;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The real main window on the test scene. Needs a display. */
class MainWindowTest {
    @TempDir
    Path dir;

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private MainWindow window;

    /** Opens the window as the program does. */
    static MainWindow open(Scene scene, ExecutorService background) throws Exception {
        MainWindow[] w = new MainWindow[1];
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            w[0] = new MainWindow(EN, null, scene.disks, scene.places, scene.passwords, scene.engine, scene.restorer,
                    new DesktopLauncher(), background, scene.xdg, scene.clock, scene.jobs);
            MainPresenter presenter = new MainPresenter(w[0], EN, scene.jobs, scene.history, scene.places,
                    scene.passwords, scene.backups, scene.syncs, null, background, new EdtExecutor(), scene.clock,
                    scene.home, () -> { });
            w[0].bind(presenter);
            w[0].setVisible(true);
            presenter.start();
        });
        return w[0];
    }

    @AfterEach
    void close() throws Exception {
        if (window != null) onEdt(window::dispose);
        background.shutdownNow();
    }

    @Test
    void theJobsShowWithTheirStateAndTheSelectedOnesDetails() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Scene scene = new Scene(dir, dir.resolve("home"), dir.resolve("media/BACKUP"), EN);
        window = open(scene, background);
        onEdt(() -> {
            assertEquals(3, window.jobList().getModel().getSize());
            assertEquals(MainView.State.OK, window.jobList().getModel().getElementAt(0).state());
            assertEquals(MainView.State.WARNING, window.jobList().getModel().getElementAt(2).state());
            assertEquals("Home folder", find(window, "detail.title", JLabel.class).getText());
            assertTrue(find(window, "detail.headline", JLabel.class).getText().contains("Backed up Today 09:12"));
        });
        await(() -> find(window, "detail.subline", JLabel.class).getText().contains("5 backups"));
        onEdt(() -> window.jobList().setSelectedIndex(1));
        onEdt(() -> assertTrue(find(window, "detail.headline", JLabel.class).getText().contains("Synced")));
    }
}

package eu.amadeos.apps.backup.ui.sync;

import eu.amadeos.apps.backup.Scene;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.JTable;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static eu.amadeos.apps.backup.Components.find;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SyncPreviewDialogTest {
    @TempDir
    Path dir;

    @Test
    void thePreviewListsEveryChangeWithItsReason() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Scene scene = new Scene(dir, dir.resolve("home"), dir.resolve("media/BACKUP"), EN);
        Files.createDirectories(scene.stick.resolve("Music/Old"));
        Files.writeString(scene.stick.resolve("Music/Old/gone.mp3"), "x");
        List<SyncPlan> plans = scene.syncs.plan(scene.syncJob, Cancellation.none());
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            SyncPreviewDialog d = new SyncPreviewDialog(null, EN, new Formats(EN, Scene.ZONE), scene.syncJob, plans);
            JTable table = find(d.getContentPane(), "preview.table", JTable.class);
            assertEquals("COPY", table.getValueAt(0, 0));
            assertEquals("Bach/", table.getValueAt(0, 1));
            assertEquals("new folder", table.getValueAt(0, 3));
            assertEquals("REMOVE", table.getValueAt(table.getRowCount() - 1, 0));
            assertEquals("no longer in the source", table.getValueAt(table.getRowCount() - 1, 3));
            String summary = find(d.getContentPane(), "preview.summary", JLabel.class).getText();
            assertTrue(summary.startsWith("4 to copy (330 KB), 0 to update, 1 to remove"), summary);
            assertTrue(d.startButton().isEnabled());
            d.dispose();
        });
    }

    @Test
    void nothingToDoMeansNothingToStart() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(a.resolve("x.txt"), "x");
        Files.copy(a.resolve("x.txt"), b.resolve("x.txt"), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        var job = eu.amadeos.apps.backup.domain.Job.sync("ab", "A to B",
                List.of(new eu.amadeos.apps.backup.domain.FolderPair(a, b)),
                eu.amadeos.apps.backup.domain.SyncOptions.DEFAULT);
        var trash = new eu.amadeos.apps.backup.sync.Trash(dir.resolve("trash"), dir, "1000");
        var plan = new eu.amadeos.apps.backup.sync.SyncPlanner(trash).plan(job.pairs().getFirst(), job.sync(),
                eu.amadeos.apps.backup.sync.Patterns.NONE, Cancellation.none());
        onEdt(() -> {
            SyncPreviewDialog d = new SyncPreviewDialog(null, EN, new Formats(EN, Scene.ZONE), job, List.of(plan));
            assertEquals(EN.get("preview.nothing"), find(d.getContentPane(), "preview.summary", JLabel.class).getText());
            assertFalse(d.startButton().isEnabled());
            d.dispose();
        });
    }
}

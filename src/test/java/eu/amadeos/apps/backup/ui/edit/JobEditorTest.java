package eu.amadeos.apps.backup.ui.edit;

import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTabbedPane;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;

import static eu.amadeos.apps.backup.Components.find;
import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JobEditorTest {
    @TempDir
    Path dir;

    @Test
    void aSyncIsEditedWithItsPairsAndPatterns() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Job sync = Job.sync("music", "Music", List.of(new FolderPair(dir.resolve("Music"), dir.resolve("stick/Music"))),
                SyncOptions.DEFAULT);
        Places places = new Places(new Disks(dir.resolve("none"), dir, dir));
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            JobEditor editor = new JobEditor(null, EN, sync, false, places, dir);
            assertEquals(List.of("General", "Leave Out", "When", "Commands"), titles(find(editor.getContentPane(),
                    "editor.tabs", JTabbedPane.class)));
            assertEquals(1, editor.pairModel().getRowCount());
            editor.nameField().setText("Music to car stick");
            editor.patternArea().setText("*.m3u\n\n  Podcasts  \n");
            assertNull(editor.problem());
            Job edited = editor.edited();
            assertEquals("Music to car stick", edited.name());
            assertEquals(List.of("*.m3u", "Podcasts"), edited.excludes());
            assertEquals(sync.pairs(), edited.pairs());
            editor.nameField().setText(" ");
            assertEquals(EN.get("editor.problem.name"), editor.problem());
            editor.dispose();
        });
    }

    @Test
    void aBackupHasNoCommandsTabButKeep() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Job backup = Job.backup("docs", "Docs", List.of(dir.resolve("Documents")), Target.folder(dir.resolve("repo")));
        onEdt(() -> {
            JobEditor editor = new JobEditor(null, EN, backup, true, new Places(new Disks(dir, dir, dir)), dir);
            assertEquals("New backup: Docs", editor.getTitle());
            assertEquals(List.of("General", "Leave Out", "When and Keep"), titles(find(editor.getContentPane(),
                    "editor.tabs", JTabbedPane.class)));
            editor.dispose();
        });
    }

    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new java.util.ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }
}

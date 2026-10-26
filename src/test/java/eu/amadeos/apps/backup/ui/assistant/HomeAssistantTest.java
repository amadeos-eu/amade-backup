package eu.amadeos.apps.backup.ui.assistant;

import eu.amadeos.apps.backup.Scene;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.commons.desktop.SessionPasswordStore;
import eu.amadeos.apps.backup.domain.HomeLayout;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.theme.DesktopTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static eu.amadeos.apps.backup.Components.onEdt;
import static eu.amadeos.apps.backup.TestTexts.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class HomeAssistantTest {
    @TempDir
    Path dir;

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    @Test
    void theStepsLeadToAHomeBackupOnTheDisk() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        // A stick at /media/ralf/BACKUP; it need not exist to be listed.
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(home.resolve("Documents"));
        Path sdb1 = Files.createFile(dir.resolve("sdb1"));
        Path byUuid = Files.createDirectories(dir.resolve("by-uuid"));
        Files.createSymbolicLink(byUuid.resolve(Scene.DISK_UUID), sdb1);
        Files.writeString(dir.resolve("mounts"), sdb1 + " /media/ralf/BACKUP exfat rw 0 0\n");
        Disks disks = new Disks(dir.resolve("mounts"), byUuid, dir.resolve("no-labels"));
        Job existing = Job.home("home-folder", "Home folder", home, Target.folder(dir.resolve("old")),
                HomeArea.defaults(), List.of());
        onEdt(() -> {
            AmadeUI.install(DesktopTheme.WARM_MOTIF);
            HomeAssistant a = new HomeAssistant(null, EN, new Formats(EN, Scene.ZONE), new HomeLayout(home), disks,
                    new Places(disks), new Passwords(new SessionPasswordStore()), List.of(existing), background,
                    Clock.fixed(Scene.NOW, Scene.ZONE));
            // the disk is offered and chosen
            assertEquals(0, a.step());
            assertTrue(a.nextButton().isEnabled());
            a.nextButton().doClick();
            assertEquals(1, a.step());
            assertFalse(a.areaBoxes().get(HomeArea.VIDEOS).isSelected());
            a.areaBoxes().get(HomeArea.MUSIC).doClick();
            a.nextButton().doClick();
            a.nextButton().doClick();
            assertEquals(3, a.step());
            assertFalse(a.nextButton().isEnabled(), "no password yet");
            a.passwordPanel().setPassword("a long password", false);
            assertFalse(a.nextButton().isEnabled(), "not yet kept");
            a.passwordPanel().setPassword("a long password", true);
            assertTrue(a.nextButton().isEnabled());
            Job job = a.job();
            assertEquals("home-folder-2", job.id());
            assertTrue(job.target().onRemovableDisk());
            assertEquals(Scene.DISK_UUID, job.target().diskUuid());
            assertEquals(Path.of("amadeos-backup/home-folder-2"), job.target().path());
            assertFalse(job.homeAreas().contains(HomeArea.MUSIC));
            assertTrue(job.homeAreas().contains(HomeArea.DOCUMENTS));
            a.dispose();
        });
    }
}

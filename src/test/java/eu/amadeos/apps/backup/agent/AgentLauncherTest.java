package eu.amadeos.apps.backup.agent;

import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.run.JobLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLauncherTest {
    @TempDir
    Path dir;

    @Test
    void theWindowStartsTheHelperOnlyWhenAJobRunsByItself() throws Exception {
        int[] starts = {0};
        AgentLauncher launcher = new AgentLauncher(dir.resolve("agent"), dir.resolve("startup.conf"), () -> starts[0]++);
        launcher.ensureRunning(List.of(PlannerTest.job(Schedule.MANUAL)));
        assertEquals(0, starts[0]);
        launcher.ensureRunning(List.of(PlannerTest.job(Schedule.DEFAULT)));
        assertEquals(1, starts[0]);

        try (JobLock held = JobLock.tryAcquire(dir.resolve("agent")).orElseThrow()) {
            assertTrue(launcher.running());
            launcher.ensureRunning(List.of(PlannerTest.job(Schedule.DEFAULT)));
            assertEquals(1, starts[0]);
        }
        assertFalse(launcher.running());
    }

    @Test
    void notWhenItIsSwitchedOffInTheSettings() throws Exception {
        int[] starts = {0};
        Files.writeString(dir.resolve("startup.conf"), "# Settings > Startup\ndisabled=40-amadeos-clipboard, 60-amade-backup\n");
        AgentLauncher launcher = new AgentLauncher(dir.resolve("agent"), dir.resolve("startup.conf"), () -> starts[0]++);
        assertTrue(launcher.switchedOff());
        launcher.ensureRunning(List.of(PlannerTest.job(Schedule.DEFAULT)));
        assertEquals(0, starts[0]);
    }
}

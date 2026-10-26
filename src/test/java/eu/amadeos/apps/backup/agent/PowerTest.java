package eu.amadeos.apps.backup.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerTest {
    @TempDir
    Path sys;

    void supply(String name, String... lines) throws IOException {
        Path dir = Files.createDirectories(sys.resolve(name));
        for (String line : lines) {
            String[] kv = line.split("=", 2);
            Files.writeString(dir.resolve(kv[0]), kv[1] + "\n");
        }
    }

    @Test
    void lowOnlyOnANearlyEmptyDischargingBattery() throws IOException {
        assertFalse(new Power(sys.resolve("none")).lowBattery());
        supply("AC", "type=Mains", "online=0");
        supply("BAT0", "type=Battery", "status=Discharging", "capacity=35");
        assertFalse(new Power(sys).lowBattery());
        supply("BAT0", "type=Battery", "status=Discharging", "capacity=12");
        assertTrue(new Power(sys).lowBattery());
        supply("AC", "type=Mains", "online=1");
        assertFalse(new Power(sys).lowBattery());
    }

    @Test
    void aMouseBatteryDoesNotCount() throws IOException {
        supply("hid-mouse", "type=Battery", "scope=Device", "status=Discharging", "capacity=5");
        assertFalse(new Power(sys).lowBattery());
    }
}

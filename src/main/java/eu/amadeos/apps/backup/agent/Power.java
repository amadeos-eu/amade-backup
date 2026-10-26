package eu.amadeos.apps.backup.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the computer runs on a nearly empty battery, from {@code /sys/class/power_supply} (where the Front
 * Panel's battery cell reads too): no power supply plugged in, the battery discharging and below {@link #LOW}
 * percent (with two batteries, the emptier one). A desktop computer without a battery is never low.
 */
public final class Power {
    static final int LOW = 20;

    private final Path supplies;

    public Power(Path supplies) {
        this.supplies = supplies;
    }

    public static Power system() {
        return new Power(Path.of("/sys/class/power_supply"));
    }

    public boolean lowBattery() {
        if (!Files.isDirectory(supplies)) return false;
        boolean discharging = false;
        int lowest = 100;
        try (DirectoryStream<Path> all = Files.newDirectoryStream(supplies)) {
            for (Path supply : all) {
                String type = read(supply.resolve("type"));
                if (type.equals("Mains") || type.equals("USB")) {
                    if (read(supply.resolve("online")).equals("1")) return false;
                } else if (type.equals("Battery") && !read(supply.resolve("scope")).equals("Device")) {
                    // scope Device: a mouse or keyboard battery, not the computer's
                    if (read(supply.resolve("status")).equals("Discharging")) discharging = true;
                    try {
                        lowest = Math.min(lowest, Integer.parseInt(read(supply.resolve("capacity"))));
                    } catch (NumberFormatException e) {
                        // no capacity: not counted
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        return discharging && lowest < LOW;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.US_ASCII).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}

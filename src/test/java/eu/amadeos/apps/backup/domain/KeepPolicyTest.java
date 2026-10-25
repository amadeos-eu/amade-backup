package eu.amadeos.apps.backup.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeepPolicyTest {
    @Test
    void defaultPolicyBecomesResticOptions() {
        assertEquals(List.of("--keep-last", "1", "--keep-within", "2d", "--keep-daily", "7", "--keep-weekly", "8",
                "--keep-monthly", "12", "--keep-yearly", "100"), KeepPolicy.DEFAULT.resticOptions());
        assertFalse(KeepPolicy.DEFAULT.keepsOnlyLatest());
    }

    @Test
    void theNewestBackupIsAlwaysKept() {
        KeepPolicy nothing = new KeepPolicy(0, 0, 0, 0, 0);
        assertTrue(nothing.keepsOnlyLatest());
        assertEquals(List.of("--keep-last", "1"), nothing.resticOptions());
        assertEquals(List.of("--keep-last", "1", "--keep-weekly", "4"), new KeepPolicy(0, 0, 4, 0, 0).resticOptions());
    }

    @Test
    void negativeCountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new KeepPolicy(1, -1, 0, 0, 0));
    }
}

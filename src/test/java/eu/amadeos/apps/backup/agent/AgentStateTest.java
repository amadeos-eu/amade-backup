package eu.amadeos.apps.backup.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentStateTest {
    @TempDir
    Path dir;

    @Test
    void theTimesSurviveARestart() {
        Instant first = Instant.parse("2026-10-20T08:00:00Z");
        Instant later = Instant.parse("2026-10-26T19:30:00Z");
        AgentState state = new AgentState(dir.resolve("agent.json"));
        assertEquals(first, state.knownSince("docs", first));
        assertEquals(first, state.knownSince("docs", later));
        state.setReminded("docs", later);
        state.setChecked("music", later);
        state.save();

        AgentState again = new AgentState(dir.resolve("agent.json"));
        assertEquals(first, again.knownSince("docs", later));
        assertEquals(Optional.of(later), again.reminded("docs"));
        assertEquals(Optional.of(later), again.checked("music"));
        again.retain(List.of("docs"));
        assertEquals(Optional.empty(), again.checked("music"));
    }
}

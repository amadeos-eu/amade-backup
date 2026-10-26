package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.apps.backup.bootstrap.BackupModule;
import eu.amadeos.apps.backup.domain.Comparison;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.RunResult;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.sync.SyncAction;
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.i18n.TranslationCheck;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTest {
    static final Messages EN = texts(Language.ENGLISH);

    static Messages texts(Language language) {
        return Messages.load(BackupModule.class, BackupModule.MESSAGES, Locale.forLanguageTag(language.code()));
    }

    @Test
    void everyLanguageHasEveryText() {
        assertEquals(Map.of(), TranslationCheck.problems(BackupModule.class, BackupModule.MESSAGES));
    }

    /** Every literal key in the code, e.g. msg.get("detail.never"), has a text. */
    @Test
    void everyKeyTheCodeUsesExists() throws IOException {
        Pattern call = Pattern.compile("msg\\.(?:get|format|has)\\(\"([a-z][a-z0-9.-]*[a-z0-9])\"");
        TreeSet<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = call.matcher(Files.readString(file));
                while (m.find()) if (!EN.has(m.group(1))) missing.add(m.group(1));
            }
        }
        assertEquals(new TreeSet<>(), missing);
    }

    /** Keys put together from enums and step names. */
    @Test
    void everyComposedKeyExists() {
        List<String> keys = new ArrayList<>();
        for (HomeArea a : HomeArea.values()) keys.addAll(List.of("area." + a.key(), "area." + a.key() + ".note"));
        for (JobKind k : JobKind.values()) {
            keys.addAll(List.of("list.subtitle." + k.key(), "detail.ok." + k.key(), "delete.question." + k.key(),
                    "editor.title.new." + k.key()));
        }
        for (RunResult r : RunResult.values()) keys.add("result." + r.key());
        for (EngineException.Kind k : EngineException.Kind.values()) {
            keys.add("engine." + k.key());
            if (k != EngineException.Kind.FAILED) keys.add("reason." + k.key());
        }
        for (SyncAction.Kind k : SyncAction.Kind.values()) if (k != SyncAction.Kind.MKDIR) keys.add("action." + k.key());
        for (SyncAction.Reason r : SyncAction.Reason.values()) keys.add("why." + r.key());
        for (Comparison c : Comparison.values()) keys.add("compared." + c.key());
        for (String step : HomeAssistant.STEPS) {
            for (String part : List.of("step", "heading", "intro")) keys.add("assistant." + step + "." + part);
        }
        for (String t : List.of("manual", "schedule", "plugged-in", "reminder")) keys.add("trigger." + t);
        for (String t : List.of("today", "yesterday", "week")) keys.add("timeline." + t);
        for (String d : List.of("running", "no-password", "no-repository", "before-failed")) keys.add("reason." + d);
        for (String k : List.of("new", "edit", "delete", "run", "sync", "restore", "check")) {
            keys.addAll(List.of("toolbar." + k, "toolbar." + k + ".tip"));
        }
        List<String> missing = keys.stream().filter(k -> !EN.has(k)).toList();
        assertEquals(List.of(), missing);
    }

    /** No two entries share a mnemonic letter in the menu bar or in one menu, in any language. */
    @Test
    void mnemonicsAreUnique() {
        Map<String, List<String>> groups = new HashMap<>();
        groups.put("bar", List.of("menu.backup", "menu.edit", "menu.options", "menu.help"));
        groups.put("backup", List.of("menu.backup.home", "menu.backup.new", "menu.backup.sync", "menu.backup.open",
                "menu.backup.run", "menu.backup.restore", "menu.backup.check", "menu.backup.sheet", "menu.backup.log",
                "menu.backup.exit"));
        groups.put("backup-sync", List.of("menu.backup.run-sync", "menu.backup.restore", "menu.backup.log"));
        groups.put("edit", List.of("menu.edit.job", "menu.edit.delete"));
        groups.put("password", List.of("password.first", "password.second", "password.generate", "password.show",
                "password.print", "password.kept"));
        groups.put("assistant", List.of("assistant.back", "assistant.next", "assistant.where.folder",
                "assistant.where.choose", "assistant.where.refresh"));
        groups.put("assistant-what", List.of("assistant.back", "assistant.next", "leave-out.caches", "leave-out.large",
                "leave-out.one-file-system"));
        groups.put("assistant-start", List.of("assistant.back", "assistant.finish", "assistant.start.now"));
        groups.put("editor-backup", List.of("editor.name", "editor.target", "editor.choose", "editor.sources",
                "editor.add", "editor.remove"));
        groups.put("editor-sync", List.of("editor.name", "editor.pairs", "editor.add-pair", "editor.remove",
                "editor.direction", "editor.compare", "sync.ignore-existing", "sync.size-only", "sync.skip-newer"));
        groups.put("schedule", List.of("schedule.choice.manual", "schedule.choice.daily", "schedule.choice.weekly",
                "schedule.choice.plugged-in"));
        groups.put("commands", List.of("editor.before", "editor.after", "sync.stop-if-before-fails"));
        groups.put("restore", List.of("restore.find", "restore.find.button", "restore.show", "restore.open",
                "restore.all", "restore.to", "restore.restore"));
        for (Language language : Language.values()) {
            Messages texts = texts(language);
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                Map<Integer, String> used = new HashMap<>();
                for (String key : group.getValue()) {
                    Mnemonics.Text text = Mnemonics.parse(texts.get(key));
                    assertTrue(text.index() >= 0, language + " " + key + " has no mnemonic");
                    String before = used.put(text.keyCode(), key);
                    assertEquals(null, before, language + " " + group.getKey() + ": " + key + " and " + before
                            + " share a letter");
                }
            }
        }
    }

    @Test
    void windowTitlesAndTheReadme() {
        assertEquals("Sicherung & Abgleich", texts(Language.GERMAN).get("window.title"));
        assertTrue(texts(Language.ITALIAN).format("readme.text", "amadeos-backup", "home").contains("restic --repo home"));
    }
}

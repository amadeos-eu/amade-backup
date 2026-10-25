package eu.amadeos.apps.backup.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLayoutTest {
    @TempDir
    Path home;

    private void file(String relative, int size) throws IOException {
        Path p = home.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[size]);
    }

    @Test
    void pathsFallIntoTheirAreas() throws IOException {
        HomeLayout layout = new HomeLayout(home);
        assertEquals(Optional.of(HomeArea.DOCUMENTS), layout.areaOf(Path.of("Documents/letter.odt")));
        assertEquals(Optional.of(HomeArea.DESKTOP), layout.areaOf(Path.of("Templates")));
        assertEquals(Optional.of(HomeArea.PERSONAL_DATA), layout.areaOf(Path.of(".local/share/amadeos/mail/inbox")));
        assertEquals(Optional.of(HomeArea.SETTINGS), layout.areaOf(Path.of(".config/amadeos")));
        assertEquals(Optional.of(HomeArea.OTHER), layout.areaOf(Path.of("Projects/x.java")));
        assertEquals(Optional.empty(), layout.areaOf(Path.of(".cache/mozilla")));
        assertEquals(Optional.empty(), layout.areaOf(Path.of(".local/share/Trash/files/old.txt")));
    }

    @Test
    void translatedUserFoldersComeFromUserDirs() throws IOException {
        Files.createDirectories(home.resolve(".config"));
        Files.writeString(home.resolve(".config/user-dirs.dirs"),
                "XDG_DOCUMENTS_DIR=\"$HOME/Dokumente\"\nXDG_PICTURES_DIR=\"$HOME/Bilder\"\n");
        HomeLayout layout = new HomeLayout(home);
        assertEquals(Optional.of(HomeArea.DOCUMENTS), layout.areaOf(Path.of("Dokumente/brief.odt")));
        assertEquals(Optional.of(HomeArea.PICTURES), layout.areaOf(Path.of("Bilder")));
    }

    @Test
    void switchedOffAreasCachesAndTheTrashAreLeftOut() throws IOException {
        file("Documents/a.txt", 1);
        file("Videos/film.mkv", 1);
        file(".cache/x", 1);
        file(".config/app.conf", 1);
        file(".local/share/amadeos/mail.db", 1);
        file(".local/share/Trash/files/old", 1);
        file(".local/share/recently-used.xbel", 1);
        file(".local/state/x", 1);
        java.util.Set<HomeArea> included = EnumSet.of(HomeArea.DOCUMENTS, HomeArea.PERSONAL_DATA);
        List<Path> out = new HomeLayout(home).excludes(included);
        assertTrue(out.contains(home.resolve("Videos")));
        assertTrue(out.contains(home.resolve(".cache")));
        assertTrue(out.contains(home.resolve(".config")));
        assertTrue(out.contains(home.resolve(".local/share/Trash")));
        assertTrue(out.contains(home.resolve(".local/share/recently-used.xbel")));
        assertTrue(out.contains(home.resolve(".local/state")));
        assertTrue(!out.contains(home.resolve("Documents")));
        assertTrue(!out.contains(home.resolve(".local/share/amadeos")));
        assertTrue(!out.contains(home.resolve(".local")), "mixed folders are split, not left out whole");
    }

    @Test
    void measuringCountsBytesPerArea() throws IOException {
        file("Documents/a.txt", 100);
        file("Documents/sub/b.txt", 50);
        file("Pictures/p.png", 1000);
        file(".cache/big", 5000);
        HomeLayout.Sizes sizes = new HomeLayout(home).measure(() -> false);
        assertEquals(150, sizes.bytes(EnumSet.of(HomeArea.DOCUMENTS)));
        assertEquals(2, sizes.files(EnumSet.of(HomeArea.DOCUMENTS)));
        assertEquals(1150, sizes.bytes(EnumSet.of(HomeArea.DOCUMENTS, HomeArea.PICTURES)));
        assertEquals(5000, sizes.leftOutBytes());
    }

}

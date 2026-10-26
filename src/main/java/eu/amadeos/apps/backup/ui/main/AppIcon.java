package eu.amadeos.apps.backup.ui.main;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** The program's icon from {@code /icons/amade-backup.png}. */
public final class AppIcon {
    private AppIcon() {
    }

    public static Image image() {
        try (InputStream in = AppIcon.class.getResourceAsStream("/icons/amade-backup.png")) {
            if (in != null) return ImageIO.read(in);
        } catch (IOException e) {
            // no icon then
        }
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    public static Icon icon(int size) {
        return new ImageIcon(image().getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }
}

package eu.amadeos.apps.backup.domain;

import java.util.EnumSet;
import java.util.Set;

/** The parts of a home folder that the home backup lets you switch on and off. */
public enum HomeArea {
    DOCUMENTS(true), PICTURES(true), MUSIC(true), VIDEOS(false), DOWNLOADS(false),
    /** Desktop, Templates and Public. */
    DESKTOP(true),
    /** Mail, calendars and contacts of the amade programs, in {@code ~/.local/share/amadeos}. */
    PERSONAL_DATA(true),
    /** {@code ~/.config} and the other hidden folders and files. */
    SETTINGS(true),
    /** Every other folder and file in the home folder. */
    OTHER(true);

    private final boolean byDefault;

    HomeArea(boolean byDefault) {
        this.byDefault = byDefault;
    }

    public String key() {
        return name().toLowerCase().replace('_', '-');
    }

    public boolean byDefault() {
        return byDefault;
    }

    /** Everything but Videos and Downloads, which are large and usually kept elsewhere or downloaded again. */
    public static Set<HomeArea> defaults() {
        Set<HomeArea> areas = EnumSet.noneOf(HomeArea.class);
        for (HomeArea area : values()) if (area.byDefault) areas.add(area);
        return areas;
    }
}

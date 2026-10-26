package eu.amadeos.apps.backup.ui.main;

import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.border.BevelBorder;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Backup, Edit, Options and Help. */
public final class MainMenuBar extends JMenuBar {
    private final Messages msg;
    private final List<JMenuItem> jobItems = new ArrayList<>();
    private final List<JMenuItem> backupItems = new ArrayList<>();
    private final JMenuItem run;

    public MainMenuBar(MainPresenter presenter, Messages msg, LocaleSettings locales) {
        EdtExecutor.requireEdt();
        this.msg = msg;
        setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));

        JMenu backup = menu("menu.backup");
        backup.add(item("menu.backup.home", KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                presenter::newHomeBackup));
        backup.add(item("menu.backup.new", null, presenter::newBackup));
        backup.add(item("menu.backup.sync", null, presenter::newSync));
        backup.add(item("menu.backup.open", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK),
                presenter::openExisting));
        backup.addSeparator();
        run = item("menu.backup.run", KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK),
                presenter::backUpNow);
        backup.add(run);
        jobItems.add(run);
        backupItems.add(backup.add(item("menu.backup.restore", KeyStroke.getKeyStroke(KeyEvent.VK_R,
                InputEvent.CTRL_DOWN_MASK), presenter::restore)));
        backupItems.add(backup.add(item("menu.backup.check", null, presenter::check)));
        backupItems.add(backup.add(item("menu.backup.sheet", null, presenter::printRecoverySheet)));
        jobItems.add(backup.add(item("menu.backup.log", KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
                presenter::showLog)));
        backup.addSeparator();
        backup.add(item("menu.backup.exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                presenter::exit));

        JMenu edit = menu("menu.edit");
        jobItems.add(edit.add(item("menu.edit.job", KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK),
                presenter::edit)));
        jobItems.add(edit.add(item("menu.edit.delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), presenter::delete)));

        JMenu options = menu("menu.options");
        if (locales != null) options.add(languageMenu(presenter, locales));

        JMenu help = menu("menu.help");
        help.add(item("menu.help.about", null, presenter::about));
    }

    void update(boolean job, boolean backup) {
        jobItems.forEach(i -> i.setEnabled(job));
        backupItems.forEach(i -> i.setEnabled(backup));
        Mnemonics.apply(run, msg.get(job && !backup ? "menu.backup.run-sync" : "menu.backup.run"));
    }

    /** "Desktop setting (Deutsch)" and the five languages; the choice applies at the next start. */
    private JMenu languageMenu(MainPresenter presenter, LocaleSettings locales) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get("menu.options.language"));
        menu.setName("menu.options.language");
        Messages commons = Messages.commons(msg.locale());
        List<Optional<Language>> choices = new ArrayList<>();
        choices.add(Optional.empty());
        for (Language language : Language.values()) choices.add(Optional.of(language));
        ButtonGroup group = new ButtonGroup();
        Optional<Language> current = locales.applicationLanguage();
        for (Optional<Language> choice : choices) {
            String label = choice.map(Language::nativeName).orElseGet(() -> commons.format("language.follow-desktop",
                    locales.desktopResolution().language().nativeName()));
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, choice.equals(current));
            item.setName(choice.map(Language::code).orElse(LocaleSettings.FOLLOW_DESKTOP));
            item.addActionListener(e -> presenter.setLanguage(choice));
            group.add(item);
            menu.add(item);
            if (choice.isEmpty()) menu.addSeparator();
        }
        return menu;
    }

    private JMenu menu(String key) {
        JMenu menu = Mnemonics.apply(new JMenu(), msg.get(key));
        menu.setName(key);
        add(menu);
        return menu;
    }

    private JMenuItem item(String key, KeyStroke accelerator, Runnable action) {
        JMenuItem item = Mnemonics.apply(new JMenuItem(), msg.get(key));
        item.setName(key);
        if (accelerator != null) item.setAccelerator(accelerator);
        item.addActionListener(e -> action.run());
        return item;
    }
}

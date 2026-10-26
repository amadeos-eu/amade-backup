package eu.amadeos.apps.backup.bootstrap;

import eu.amadeos.apps.backup.ui.main.MainPresenter;
import eu.amadeos.apps.backup.ui.main.MainWindow;
import eu.amadeos.commons.app.AmadeApplication;
import eu.amadeos.commons.app.AppInfo;

public final class AmadeBackupApplication {
    public static final AppInfo APP = new AppInfo("amade-backup", "Backup & Sync", "backup");

    private AmadeBackupApplication() {
    }

    /** The version from the jar's manifest; "dev" when run from the IDE. */
    public static String version() {
        String version = AmadeBackupApplication.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    // --8<-- [start:main]
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--version") || args[0].equals("-v"))) {
            System.out.println("amade-backup " + version());
            return;
        }
        AmadeApplication.launch(APP, injector -> {
            MainWindow window = injector.getInstance(MainWindow.class);
            MainPresenter presenter = injector.getInstance(MainPresenter.class);
            window.bind(presenter);
            window.setVisible(true);
            presenter.start();
        }, new BackupModule());
    }
    // --8<-- [end:main]
}

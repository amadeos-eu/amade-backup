package eu.amadeos.apps.backup.ui.main;

import com.google.inject.Inject;
import eu.amadeos.apps.backup.bootstrap.AmadeBackupApplication;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.HomeLayout;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.RunRecord;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.run.Restorer;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.assistant.HomeAssistant;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.common.PasswordDialog;
import eu.amadeos.apps.backup.ui.common.PasswordPanel;
import eu.amadeos.apps.backup.ui.common.ProgressDialog;
import eu.amadeos.apps.backup.ui.common.RecoverySheet;
import eu.amadeos.apps.backup.ui.edit.JobEditor;
import eu.amadeos.apps.backup.ui.restore.RestoreWindow;
import eu.amadeos.apps.backup.ui.sync.SyncPreviewDialog;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.LocaleSettings;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;
import eu.amadeos.commons.swing.StandardGlyph;
import eu.amadeos.commons.swing.ToolIcon;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.print.PrinterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * The main window: the jobs on the left with a state light each, the selected job on the right with its
 * state, its settings and its history. Toolbar and menus act on the selected job.
 */
public final class MainWindow extends JFrame implements MainView {
    static final Color OK = new Color(0x3F8A4A);
    static final Color WARNING = new Color(0xB8862B);
    static final Color PROBLEM = new Color(0xA94442);

    private final Messages msg;
    private final LocaleSettings locales;
    private final Formats formats;
    private final Disks disks;
    private final Places places;
    private final Passwords passwords;
    private final BackupEngine engine;
    private final Restorer restorer;
    private final DesktopLauncher launcher;
    private final ExecutorService background;
    private final XdgDirectories xdg;
    private final Clock clock;
    private final JobStore jobStore;

    private final DefaultListModel<JobRow> jobs = new DefaultListModel<>();
    private final JList<JobRow> jobList = new JList<>(jobs);
    private final JLabel title = new JLabel(" ");
    private final JLabel headline = new JLabel(" ");
    private final JLabel subline = new JLabel(" ");
    private final JPanel facts = new JPanel(new GridBagLayout());
    private final HistoryModel historyModel = new HistoryModel();
    private final JTable historyTable;
    private final JLabel status = new JLabel(" ");
    private final List<JComponent> jobActions = new ArrayList<>();
    private final List<JComponent> backupActions = new ArrayList<>();
    private JButton runButton;
    private JButton toolbarRun;
    private MainPresenter presenter;
    private MainMenuBar menuBar;
    private boolean updating;

    @Inject
    public MainWindow(Messages msg, LocaleSettings locales, Disks disks, Places places, Passwords passwords,
                      BackupEngine engine, Restorer restorer, DesktopLauncher launcher, ExecutorService background,
                      XdgDirectories xdg, Clock clock, JobStore jobStore) {
        super(msg.get("window.title"));
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.locales = locales;
        this.formats = new Formats(msg, clock.getZone());
        this.historyTable = new JTable(historyModel);
        this.disks = disks;
        this.places = places;
        this.passwords = passwords;
        this.engine = engine;
        this.restorer = restorer;
        this.launcher = launcher;
        this.background = background;
        this.xdg = xdg;
        this.clock = clock;
        this.jobStore = jobStore;
        setName("main");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setIconImage(AppIcon.image());
    }

    public void bind(MainPresenter presenter) {
        this.presenter = presenter;
        // Runs of the helper appear in the list while the window is open
        javax.swing.Timer refresh = new javax.swing.Timer(5000, e -> presenter.refreshIfChanged());
        refresh.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                refresh.stop();
            }
        });
        menuBar = new MainMenuBar(presenter, msg, locales);
        setJMenuBar(menuBar);
        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.setBorder(new EmptyBorder(8, 8, 4, 8));
        center.add(jobListPane(), BorderLayout.WEST);
        center.add(detailPane(), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        status.setForeground(AmadeUI.theme().textInactive());
        status.setBorder(new EmptyBorder(2, 10, 4, 10));
        status.setName("status");
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                presenter.exit();
            }
        });
        setPreferredSize(new Dimension(1000, 640));
        pack();
        setLocationRelativeTo(null);
    }

    private JToolBar toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(tool("toolbar.new", StandardGlyph.NEW, presenter::newHomeBackup, false));
        JButton edit = tool("toolbar.edit", StandardGlyph.EDIT, presenter::edit, false);
        JButton delete = tool("toolbar.delete", StandardGlyph.DELETE, presenter::delete, false);
        bar.add(edit);
        bar.add(delete);
        jobActions.add(edit);
        jobActions.add(delete);
        bar.addSeparator();
        toolbarRun = tool("toolbar.run", StandardGlyph.SAVE, presenter::backUpNow, false);
        JButton restore = tool("toolbar.restore", StandardGlyph.OPEN, presenter::restore, false);
        JButton check = tool("toolbar.check", StandardGlyph.FIND, presenter::check, false);
        bar.add(toolbarRun);
        bar.add(restore);
        bar.add(check);
        jobActions.add(toolbarRun);
        backupActions.add(restore);
        backupActions.add(check);
        return bar;
    }

    private JButton tool(String key, StandardGlyph glyph, Runnable action, boolean iconOnly) {
        JButton b = new JButton(iconOnly ? null : Mnemonics.strip(msg.get(key)), new ToolIcon(glyph));
        b.setName(key);
        b.setFocusable(false);
        b.setToolTipText(msg.get(key + ".tip"));
        b.addActionListener(e -> action.run());
        return b;
    }

    private JComponent jobListPane() {
        jobList.setName("jobs");
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jobList.setCellRenderer(new JobRenderer());
        jobList.addListSelectionListener(e -> {
            if (!updating && !e.getValueIsAdjusting()) presenter.select(jobList.getSelectedIndex());
        });
        JScrollPane pane = new JScrollPane(jobList);
        pane.setPreferredSize(new Dimension(260, 400));
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(pane, BorderLayout.CENTER);
        JPanel add = new JPanel(new GridLayout(1, 2, 6, 0));
        JButton home = Mnemonics.apply(new JButton(), msg.get("list.add-home"));
        home.setName("list.add-home");
        home.addActionListener(e -> presenter.newHomeBackup());
        JButton sync = Mnemonics.apply(new JButton(), msg.get("list.add-sync"));
        sync.setName("list.add-sync");
        sync.addActionListener(e -> presenter.newSync());
        add.add(home);
        add.add(sync);
        panel.add(add, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent detailPane() {
        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), new EmptyBorder(10, 12, 10, 12)));
        head.setBackground(AmadeUI.theme().fieldBackground());
        JPanel names = new JPanel(new GridLayout(3, 1));
        names.setOpaque(false);
        title.setFont(title.getFont().deriveFont(Font.BOLD, AmadeUI.scaled(18f)));
        title.setName("detail.title");
        headline.setName("detail.headline");
        subline.setName("detail.subline");
        subline.setForeground(AmadeUI.theme().textInactive());
        names.add(title);
        names.add(headline);
        names.add(subline);
        head.add(names, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.setOpaque(false);
        runButton = new JButton(Mnemonics.strip(msg.get("toolbar.run")));
        runButton.setName("detail.run");
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD));
        runButton.addActionListener(e -> presenter.backUpNow());
        JButton restore = new JButton(Mnemonics.strip(msg.get("toolbar.restore")));
        restore.setName("detail.restore");
        restore.addActionListener(e -> presenter.restore());
        buttons.add(runButton);
        buttons.add(restore);
        jobActions.add(runButton);
        backupActions.add(restore);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(buttons, BorderLayout.NORTH);
        head.add(wrap, BorderLayout.EAST);

        facts.setBorder(new TitledBorder(msg.get("detail.settings")));
        facts.setName("detail.facts");

        historyTable.setName("history");
        historyTable.setRowHeight(Math.round(AmadeUI.scaled(20)));
        historyTable.setShowGrid(false);
        historyTable.setFillsViewportHeight(true);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        historyTable.getColumnModel().getColumn(6).setPreferredWidth(220);
        historyTable.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                RunRecord run = historyModel.run(r);
                if (!sel && run != null) {
                    setForeground(switch (run.result()) {
                        case OK -> OK;
                        case FAILED -> PROBLEM;
                        case TARGET_MISSING, CANCELLED -> AmadeUI.theme().textInactive();
                        case INCOMPLETE -> WARNING;
                    });
                }
                return this;
            }
        });
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c <= 5; c++) historyTable.getColumnModel().getColumn(c).setCellRenderer(right);
        JScrollPane historyPane = new JScrollPane(historyTable);
        historyPane.setPreferredSize(new Dimension(680, 200));
        JPanel historyPanel = new JPanel(new BorderLayout(0, 4));
        historyPanel.setBorder(new TitledBorder(msg.get("detail.history")));
        historyPanel.add(historyPane, BorderLayout.CENTER);
        JPanel historyButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton log = Mnemonics.apply(new JButton(), msg.get("detail.log"));
        log.setName("detail.log");
        log.addActionListener(e -> presenter.showLog());
        historyButtons.add(log);
        jobActions.add(log);
        historyPanel.add(historyButtons, BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.add(facts, BorderLayout.NORTH);
        body.add(historyPanel, BorderLayout.CENTER);
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.add(head, BorderLayout.NORTH);
        detail.add(body, BorderLayout.CENTER);
        return detail;
    }

    // ---- MainView ---------------------------------------------------------------------------------------------

    @Override
    public void showJobs(List<JobRow> rows, int selected) {
        updating = true;
        try {
            jobs.clear();
            rows.forEach(jobs::addElement);
            if (selected >= 0) jobList.setSelectedIndex(selected); else jobList.clearSelection();
        } finally {
            updating = false;
        }
    }

    @Override
    public void showDetail(Detail detail) {
        boolean has = detail != null;
        boolean backup = has && detail.job().kind() == eu.amadeos.apps.backup.domain.JobKind.BACKUP;
        jobActions.forEach(c -> c.setEnabled(has));
        backupActions.forEach(c -> c.setEnabled(backup));
        backupActions.forEach(c -> c.setVisible(!has || backup));
        menuBar.update(has, backup);
        String runText = Mnemonics.strip(msg.get(has && !backup ? "toolbar.sync" : "toolbar.run"));
        runButton.setText(runText);
        toolbarRun.setText(runText);
        facts.removeAll();
        if (!has) {
            title.setText(msg.get("detail.empty.title"));
            headline.setText(msg.get("detail.empty.text"));
            headline.setForeground(AmadeUI.theme().textActive());
            subline.setText(" ");
        } else {
            title.setText(detail.job().name());
            headline.setText("●  " + detail.headline());
            headline.setForeground(switch (detail.state()) {
                case OK -> OK;
                case WARNING -> WARNING;
                case PROBLEM -> PROBLEM;
                case IDLE -> AmadeUI.theme().textActive();
            });
            subline.setText(detail.subline());
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 6, 3, 6);
            g.anchor = GridBagConstraints.NORTHWEST;
            int y = 0;
            for (String[] fact : detail.facts()) {
                g.gridy = y++;
                g.gridx = 0;
                g.weightx = 0;
                g.fill = GridBagConstraints.NONE;
                JLabel key = new JLabel(fact[0]);
                key.setFont(key.getFont().deriveFont(Font.BOLD));
                facts.add(key, g);
                g.gridx = 1;
                g.weightx = 1;
                g.fill = GridBagConstraints.HORIZONTAL;
                facts.add(new JLabel(fact[1]), g);
            }
            g.gridx = 2;
            g.gridy = 0;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            g.gridheight = Math.max(1, y);
            JButton change = Mnemonics.apply(new JButton(), msg.get("detail.change"));
            change.setName("detail.change");
            change.addActionListener(e -> presenter.edit());
            facts.add(change, g);
        }
        facts.revalidate();
        facts.repaint();
    }

    @Override
    public void showHistory(Job job, List<RunRecord> runs) {
        historyModel.set(job, runs);
    }

    @Override
    public void showStatus(String text) {
        status.setText(text);
    }

    @Override
    public void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    @Override
    public Optional<HomeAssistant.Result> homeAssistant() {
        HomeAssistant assistant = new HomeAssistant(this, msg, formats, new HomeLayout(xdg.home()), disks, places,
                passwords, jobStore.load(), background, clock);
        return assistant.showAndWait();
    }

    @Override
    public Optional<Job> editJob(Job job, boolean isNew) {
        return new JobEditor(this, msg, job, isNew, places, xdg.home()).showAndWait();
    }

    @Override
    public Optional<String> newPassword(Job job) {
        PasswordPanel panel = new PasswordPanel(msg, passwords::generate,
                p -> printRecoverySheet(job, presenter.where(job), p));
        panel.setBorder(new EmptyBorder(10, 12, 10, 12));
        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog(this, msg.format("new-password.title", job.name()));
        while (true) {
            dialog.setVisible(true);
            if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) return Optional.empty();
            if (panel.complete()) return Optional.of(panel.password());
            showError(msg.get("password.incomplete"));
        }
    }

    @Override
    public Optional<PasswordDialog.Answer> askPassword(Job job, String problem) {
        return PasswordDialog.ask(this, msg, job.name(), problem);
    }

    @Override
    public Optional<Path> chooseBackupFolder() {
        JFileChooser chooser = new JFileChooser(startFolder().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(msg.get("open.choose"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return Optional.empty();
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    /** The first plugged-in disk, else /media/<user>, else the home folder. */
    private Path startFolder() {
        var mounted = disks.mounted();
        if (!mounted.isEmpty()) return mounted.getFirst().mountPoint();
        Path media = Path.of("/media", System.getProperty("user.name", ""));
        return Files.isDirectory(media) ? media : xdg.home();
    }

    @Override
    public boolean confirmSync(Job job, List<SyncPlan> plans) {
        return new SyncPreviewDialog(this, msg, formats, job, plans).showAndWait();
    }

    @Override
    public void openRestore(Job job, Repository repository) {
        RestoreWindow window = new RestoreWindow(msg, formats, job, repository, engine, restorer, launcher, background,
                clock, xdg);
        window.setLocationRelativeTo(this);
        window.setVisible(true);
        window.load();
    }

    @Override
    public Progress progress(String title, String heading, Cancellation cancellation) {
        ProgressDialog dialog = new ProgressDialog(this, formats, title, heading, cancellation);
        dialog.setVisible(true);
        return new Progress() {
            @Override
            public void update(eu.amadeos.apps.backup.domain.BackupProgress progress) {
                dialog.update(progress);
            }

            @Override
            public void close() {
                dialog.close();
            }
        };
    }

    @Override
    public void printRecoverySheet(Job job, String where, String password) {
        try {
            new RecoverySheet(msg, formats, job, where, password, clock.instant()).print();
        } catch (PrinterException e) {
            showError(msg.format("error.print", e.getMessage()));
        }
    }

    @Override
    public boolean confirm(String title, String question) {
        return JOptionPane.showConfirmDialog(this, html(question), title, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    @Override
    public void showInfo(String text) {
        JOptionPane.showMessageDialog(this, html(text), msg.get("window.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void showError(String text) {
        JOptionPane.showMessageDialog(this, html(text), msg.get("window.title"), JOptionPane.ERROR_MESSAGE);
    }

    static String html(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return "<html><div style='width:380px'>" + escaped + "</div></html>";
    }

    @Override
    public void showLog(String title, String text) {
        JTextArea area = new JTextArea(text, 24, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(AmadeUI.scaled(12))));
        JOptionPane.showMessageDialog(this, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }

    @Override
    public void showAbout(String engineVersion) {
        String text = msg.format("about.text", presenter.version(), engineVersion.isEmpty() ? "restic" : engineVersion);
        JOptionPane.showMessageDialog(this, html(text), msg.get("about.title"), JOptionPane.INFORMATION_MESSAGE,
                AppIcon.icon(48));
    }

    @Override
    public void close() {
        setVisible(false);
        dispose();
    }

    // ---- parts ------------------------------------------------------------------------------------------------

    JList<JobRow> jobList() {
        return jobList;
    }

    JLabel headlineLabel() {
        return headline;
    }

    JTable historyTable() {
        return historyTable;
    }

    /** The history of the selected job, newest first. */
    final class HistoryModel extends AbstractTableModel {
        private Job job;
        private List<RunRecord> runs = List.of();

        void set(Job job, List<RunRecord> runs) {
            this.job = job;
            this.runs = runs;
            fireTableStructureChanged();
            if (historyTable.getColumnCount() == 7) {
                historyTable.getColumnModel().getColumn(0).setPreferredWidth(260);
                historyTable.getColumnModel().getColumn(6).setPreferredWidth(220);
            }
        }

        RunRecord run(int row) {
            return row >= 0 && row < runs.size() ? runs.get(row) : null;
        }

        @Override
        public int getRowCount() {
            return runs.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public String getColumnName(int column) {
            boolean sync = job != null && job.kind() == eu.amadeos.apps.backup.domain.JobKind.SYNC;
            String[] keys = {"when", "files", sync ? "copied" : "new", sync ? "updated" : "changed",
                    sync ? "deleted" : "removed", sync ? "written" : "added", "result"};
            return msg.get("history." + keys[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            RunRecord r = runs.get(row);
            boolean saved = r.result().saved() || r.result() == eu.amadeos.apps.backup.domain.RunResult.CANCELLED
                    && r.files() > 0;
            return switch (column) {
                case 0 -> formats.when(r.started(), clock.instant()) + (r.trigger().equals("manual") ? ""
                        : "  (" + msg.get("trigger." + r.trigger()) + ")");
                case 1 -> saved ? formats.count(r.files()) : "—";
                case 2 -> saved ? formats.count(r.added()) : "";
                case 3 -> saved ? formats.count(r.changed()) : "";
                case 4 -> saved ? formats.count(r.removed()) : "";
                case 5 -> saved ? formats.bytes(r.bytes()) : "";
                default -> result(r);
            };
        }

        private String result(RunRecord r) {
            String text = msg.get("result." + r.result().key());
            if (r.result() == eu.amadeos.apps.backup.domain.RunResult.TARGET_MISSING && !r.detail().isEmpty()) {
                return msg.format("result.target-missing.disk", r.detail());
            }
            return text;
        }
    }

    /** A job: its state light on the target symbol, the name in bold and the target below. */
    private final class JobRenderer implements javax.swing.ListCellRenderer<JobRow> {
        @Override
        public Component getListCellRendererComponent(JList<? extends JobRow> list, JobRow row, int index,
                                                      boolean selected, boolean focus) {
            var theme = AmadeUI.theme();
            String sub = selected ? "" : " color='" + String.format("#%06x", theme.textInactive().getRGB() & 0xFFFFFF) + "'";
            JLabel label = new JLabel("<html><b>" + escape(row.job().name()) + "</b><br><font" + sub + ">"
                    + escape(row.subtitle()) + "</font></html>", JobIcon.of(row), SwingConstants.LEFT);
            label.setIconTextGap(8);
            label.setBorder(new EmptyBorder(5, 6, 5, 6));
            label.setOpaque(true);
            label.setBackground(selected ? theme.selectionBackground() : theme.fieldBackground());
            label.setForeground(selected ? theme.selectionText() : theme.fieldText());
            return label;
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String appTitle() {
        return AmadeBackupApplication.APP.title();
    }
}

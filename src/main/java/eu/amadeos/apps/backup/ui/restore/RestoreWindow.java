package eu.amadeos.apps.backup.ui.restore;

import eu.amadeos.apps.backup.domain.Comparison;
import eu.amadeos.apps.backup.domain.FoundFile;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.Snapshot;
import eu.amadeos.apps.backup.domain.SnapshotEntry;
import eu.amadeos.apps.backup.domain.Timeline;
import eu.amadeos.apps.backup.engine.BackupEngine;
import eu.amadeos.apps.backup.engine.Cancellation;
import eu.amadeos.apps.backup.engine.EngineException;
import eu.amadeos.apps.backup.engine.Repository;
import eu.amadeos.apps.backup.run.Restorer;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.common.ProgressDialog;
import eu.amadeos.commons.config.XdgDirectories;
import eu.amadeos.commons.desktop.DesktopLauncher;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Restore from one backup job: the backups as a timeline on the left, the folders of the chosen backup, its
 * files compared with the computer now, and the versions of the chosen file in all backups. Find searches
 * every backup by name. Restore puts files back where they were, Restore To into another folder; an existing
 * file is never overwritten without asking.
 */
public final class RestoreWindow extends JFrame {
    static final Color CHANGED = new Color(0xB8862B);
    static final Color DELETED = new Color(0xA94442);

    /** A row of the file table: an entry of the chosen backup, or a search hit with its own backup. */
    record FileRow(SnapshotEntry entry, String snapshotId, Comparison comparison) {
    }

    /** A version of a file: unchanged through a run of backups. */
    record Version(SnapshotEntry entry, Instant firstBackup, Instant lastBackup, String snapshotId) {
    }

    private final Messages msg;
    private final Formats formats;
    private final Job job;
    private final Repository repository;
    private final BackupEngine engine;
    private final Restorer restorer;
    private final DesktopLauncher launcher;
    private final ExecutorService background;
    private final Clock clock;
    private final XdgDirectories xdg;

    private final DefaultListModel<Timeline.Row> timeline = new DefaultListModel<>();
    private final JList<Timeline.Row> timelineList = new JList<>(timeline);
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);
    private final FileModel files = new FileModel();
    private final JTable fileTable;
    private final VersionModel versions = new VersionModel();
    private final JTable versionTable;
    private final JTextField find = new JTextField(18);
    private final JComboBox<String> show;
    private final JLabel note = new JLabel(" ");
    private final JButton open;
    private final JButton restore;
    private final JButton restoreTo;
    private final Map<String, List<SnapshotEntry>> listings = new HashMap<>();
    private final Map<String, Instant> snapshotTimes = new HashMap<>();
    private List<Snapshot> snapshots = List.of();
    private Snapshot current;
    private List<FileRow> allRows = List.of();
    private boolean searching;
    private int loads;

    public RestoreWindow(Messages msg, Formats formats, Job job, Repository repository, BackupEngine engine,
                         Restorer restorer, DesktopLauncher launcher, ExecutorService background, Clock clock,
                         XdgDirectories xdg) {
        super(msg.format("restore.title", job.name()));
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.formats = formats;
        this.job = job;
        this.repository = repository;
        this.engine = engine;
        this.restorer = restorer;
        this.launcher = launcher;
        this.background = background;
        this.clock = clock;
        this.xdg = xdg;
        // The tables ask their models for the column names, which need the texts.
        fileTable = new JTable(files);
        versionTable = new JTable(versions);
        setName("restore");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        show = new JComboBox<>(new String[]{msg.get("restore.show.all"), msg.get("restore.show.changed"),
                msg.get("restore.show.deleted")});
        show.setName("restore.show");
        show.addActionListener(e -> applyFilter());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(new EmptyBorder(6, 8, 6, 8));
        JPanel findRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel findLabel = Mnemonics.apply(new JLabel(), msg.get("restore.find"));
        findLabel.setLabelFor(find);
        find.setName("restore.find");
        find.addActionListener(e -> search());
        JButton findButton = new JButton(Mnemonics.strip(msg.get("restore.find.button")));
        findButton.addActionListener(e -> search());
        findRow.add(findLabel);
        findRow.add(find);
        findRow.add(findButton);
        top.add(findRow, BorderLayout.WEST);
        JPanel showRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JLabel showLabel = Mnemonics.apply(new JLabel(), msg.get("restore.show"));
        showLabel.setLabelFor(show);
        showRow.add(showLabel);
        showRow.add(show);
        top.add(showRow, BorderLayout.EAST);

        timelineList.setName("restore.timeline");
        timelineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        timelineList.setCellRenderer(this::timelineCell);
        timelineList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Timeline.Row row = timelineList.getSelectedValue();
            if (row instanceof Timeline.Entry entry) choose(entry.snapshot());
        });
        JScrollPane timelinePane = new JScrollPane(timelineList);
        timelinePane.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(170)), 440));

        tree.setName("restore.tree");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        DefaultTreeCellRenderer treeRenderer = new DefaultTreeCellRenderer();
        treeRenderer.setLeafIcon(treeRenderer.getClosedIcon());
        tree.setCellRenderer(treeRenderer);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                expand((DefaultMutableTreeNode) e.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof Folder f) showFolder(f.path());
        });
        JScrollPane treePane = new JScrollPane(tree);
        treePane.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(230)), 440));

        fileTable.setName("restore.files");
        fileTable.setRowHeight(Math.round(AmadeUI.scaled(20)));
        fileTable.setShowGrid(false);
        fileTable.setFillsViewportHeight(true);
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fileSelected();
        });
        fileTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        configureFileColumns();
        JScrollPane filePane = new JScrollPane(fileTable);
        filePane.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(580)), 280));

        versionTable.setName("restore.versions");
        versionTable.setRowHeight(Math.round(AmadeUI.scaled(20)));
        versionTable.setShowGrid(false);
        versionTable.setFillsViewportHeight(true);
        versionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versionTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        JScrollPane versionPane = new JScrollPane(versionTable);
        versionPane.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(580)), Math.round(AmadeUI.scaled(100))));
        note.setForeground(AmadeUI.theme().textInactive());
        note.setName("restore.note");

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(filePane, BorderLayout.CENTER);
        JPanel versionBox = new JPanel(new BorderLayout(0, 4));
        versionBox.add(versionPane, BorderLayout.CENTER);
        versionBox.add(note, BorderLayout.SOUTH);
        right.add(versionBox, BorderLayout.SOUTH);
        JPanel lefts = new JPanel(new BorderLayout(8, 0));
        lefts.add(timelinePane, BorderLayout.WEST);
        lefts.add(treePane, BorderLayout.CENTER);
        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.setBorder(new EmptyBorder(0, 8, 6, 8));
        center.add(lefts, BorderLayout.WEST);
        center.add(right, BorderLayout.CENTER);

        open = button("restore.open", this::openSelected);
        JButton all = button("restore.all", this::restoreAll);
        restoreTo = button("restore.to", () -> restoreSelected(true));
        restore = button("restore.restore", () -> restoreSelected(false));
        restore.setFont(restore.getFont().deriveFont(Font.BOLD));
        JButton close = new JButton(msg.get("dialog.close"));
        close.addActionListener(e -> dispose());
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(4, 8, 8, 8));
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftButtons.add(open);
        leftButtons.add(all);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightButtons.add(restoreTo);
        rightButtons.add(restore);
        rightButtons.add(close);
        south.add(leftButtons, BorderLayout.WEST);
        south.add(rightButtons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        updateButtons();
        pack();
    }

    private JButton button(String key, Runnable action) {
        JButton b = Mnemonics.apply(new JButton(), msg.get(key));
        b.setName(key);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- loading -------------------------------------------------------------------------------------------------

    /** Reads the backups and selects the newest. */
    public void load() {
        busy(true);
        background.execute(() -> {
            try {
                List<Snapshot> list = engine.snapshots(repository);
                new EdtExecutor().execute(() -> {
                    busy(false);
                    showSnapshots(list);
                });
            } catch (EngineException e) {
                failed(e);
            }
        });
    }

    void showSnapshots(List<Snapshot> list) {
        snapshots = list;
        snapshotTimes.clear();
        for (Snapshot s : list) snapshotTimes.put(s.id(), s.time());
        timeline.clear();
        for (Timeline.Row row : Timeline.of(list, clock.instant().atZone(formats.zone()).toLocalDate(), formats.zone(),
                msg.locale())) {
            timeline.addElement(row);
        }
        if (list.isEmpty()) {
            note.setText(msg.get("restore.empty"));
            return;
        }
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i) instanceof Timeline.Entry) {
                timelineList.setSelectedIndex(i);
                break;
            }
        }
    }

    private void choose(Snapshot snapshot) {
        if (Objects.equals(current, snapshot) && !searching) return;
        current = snapshot;
        searching = false;
        configureFileColumns();
        treeRoot.removeAllChildren();
        for (String path : snapshot.paths()) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Folder(path, path + "  (" + formats.when(snapshot.time(),
                    clock.instant()) + ")"));
            node.add(new DefaultMutableTreeNode(LOADING));
            treeRoot.add(node);
        }
        treeModel.reload();
        if (treeRoot.getChildCount() > 0) {
            TreePath first = new TreePath(new Object[]{treeRoot, treeRoot.getChildAt(0)});
            tree.expandPath(first);
            tree.setSelectionPath(first);
        }
    }

    private static final String LOADING = "…";
    /** Counts the folders asked for, on the event dispatch thread. */
    private int folderRequest;

    /** A folder node: its absolute path in the backup and what the tree shows. */
    record Folder(String path, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void expand(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Folder folder)) return;
        if (node.getChildCount() != 1 || !LOADING.equals(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject())) {
            return;
        }
        Snapshot snapshot = current;
        listing(snapshot, folder.path(), entries -> {
            if (snapshot != current) return;
            node.removeAllChildren();
            for (SnapshotEntry e : entries) {
                if (!e.isDirectory()) continue;
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(new Folder(e.path(), e.name()));
                child.add(new DefaultMutableTreeNode(LOADING));
                node.add(child);
            }
            treeModel.nodeStructureChanged(node);
        });
    }

    /** The entries of a folder of a backup, read once and kept. */
    private void listing(Snapshot snapshot, String dir, java.util.function.Consumer<List<SnapshotEntry>> then) {
        String key = snapshot.id() + "\n" + dir;
        List<SnapshotEntry> cached = listings.get(key);
        if (cached != null) {
            then.accept(cached);
            return;
        }
        busy(true);
        background.execute(() -> {
            try {
                List<SnapshotEntry> entries = engine.list(repository, snapshot.id(), dir);
                new EdtExecutor().execute(() -> {
                    busy(false);
                    listings.put(key, entries);
                    then.accept(entries);
                });
            } catch (EngineException e) {
                failed(e);
            }
        });
    }

    void showFolder(String dir) {
        if (current == null) return;
        Snapshot snapshot = current;
        // Only the folder chosen last is shown, also when an earlier one takes longer to read
        int request = ++folderRequest;
        searching = false;
        configureFileColumns();
        listing(snapshot, dir, entries -> {
            if (snapshot != current || request != folderRequest) return;
            background.execute(() -> {
                List<FileRow> rows = new ArrayList<>();
                for (SnapshotEntry e : entries) rows.add(new FileRow(e, snapshot.id(), Comparison.of(e, Path.of(e.path()))));
                new EdtExecutor().execute(() -> {
                    if (snapshot != current || searching || request != folderRequest) return;
                    allRows = rows;
                    applyFilter();
                });
            });
        });
    }

    private void applyFilter() {
        int choice = show.getSelectedIndex();
        List<FileRow> rows = new ArrayList<>();
        for (FileRow r : allRows) {
            if (choice == 1 && r.comparison() == Comparison.SAME) continue;
            if (choice == 2 && r.comparison() != Comparison.DELETED) continue;
            rows.add(r);
        }
        files.set(rows);
        versions.set(List.of());
        note.setText(" ");
        updateButtons();
    }

    private void search() {
        String text = find.getText().strip();
        if (text.isEmpty()) {
            if (current != null) {
                Snapshot s = current;
                current = null;
                choose(s);
            }
            return;
        }
        String pattern = text.contains("*") || text.contains("?") ? text : "*" + text + "*";
        busy(true);
        background.execute(() -> {
            try {
                List<FoundFile> found = engine.find(repository, pattern);
                List<FileRow> rows = new ArrayList<>();
                for (FoundFile f : found) {
                    String name = f.path().substring(f.path().lastIndexOf('/') + 1);
                    SnapshotEntry entry = new SnapshotEntry(name, f.path(), f.type(), f.size(), f.modified());
                    rows.add(new FileRow(entry, f.snapshotId(), Comparison.of(entry, Path.of(f.path()))));
                }
                rows.sort((a, b) -> {
                    int c = a.entry().path().compareTo(b.entry().path());
                    return c != 0 ? c : snapshotTimes.getOrDefault(b.snapshotId(), Instant.EPOCH)
                            .compareTo(snapshotTimes.getOrDefault(a.snapshotId(), Instant.EPOCH));
                });
                new EdtExecutor().execute(() -> {
                    busy(false);
                    searching = true;
                    configureFileColumns();
                    allRows = rows;
                    applyFilter();
                    note.setText(msg.format("restore.found", formats.count(rows.size()), text));
                });
            } catch (EngineException e) {
                failed(e);
            }
        });
    }

    // ---- versions ------------------------------------------------------------------------------------------------

    private void fileSelected() {
        updateButtons();
        List<FileRow> rows = selectedRows();
        if (rows.size() != 1 || rows.getFirst().entry().isDirectory()) {
            versions.set(List.of());
            note.setText(rows.isEmpty() ? " " : msg.format("restore.selected", rows.size()));
            return;
        }
        FileRow row = rows.getFirst();
        note.setText(comparisonNote(row));
        String path = row.entry().path();
        background.execute(() -> {
            try {
                List<FoundFile> found = engine.find(repository, row.entry().name());
                List<Version> list = versionsOf(path, found, snapshotTimes);
                new EdtExecutor().execute(() -> {
                    List<FileRow> now = selectedRows();
                    if (now.size() == 1 && now.getFirst().entry().path().equals(path)) versions.set(list);
                });
            } catch (EngineException e) {
                // the versions stay empty
            }
        });
    }

    /** The versions of a file, newest first: a new version whenever size or time differ from the backup before. */
    static List<Version> versionsOf(String path, List<FoundFile> found, Map<String, Instant> snapshotTimes) {
        List<FoundFile> hits = new ArrayList<>(found.stream().filter(f -> f.path().equals(path))
                .filter(f -> snapshotTimes.containsKey(f.snapshotId())).toList());
        hits.sort((a, b) -> snapshotTimes.get(a.snapshotId()).compareTo(snapshotTimes.get(b.snapshotId())));
        Map<String, Version> byContent = new LinkedHashMap<>();
        List<Version> out = new ArrayList<>();
        Version open = null;
        String openKey = null;
        for (FoundFile f : hits) {
            String key = f.size() + "/" + f.modified();
            Instant t = snapshotTimes.get(f.snapshotId());
            String name = path.substring(path.lastIndexOf('/') + 1);
            SnapshotEntry entry = new SnapshotEntry(name, path, f.type(), f.size(), f.modified());
            if (open != null && key.equals(openKey)) {
                open = new Version(open.entry(), open.firstBackup(), t, f.snapshotId());
                out.set(out.size() - 1, open);
            } else {
                open = new Version(entry, t, t, f.snapshotId());
                openKey = key;
                out.add(open);
            }
            byContent.put(key, open);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private String comparisonNote(FileRow row) {
        return switch (row.comparison()) {
            case SAME -> msg.get("restore.note.same");
            case CHANGED -> msg.get("restore.note.changed");
            case DELETED -> msg.format("restore.note.deleted", Path.of(row.entry().path()).getParent());
        };
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    private List<FileRow> selectedRows() {
        List<FileRow> rows = new ArrayList<>();
        for (int i : fileTable.getSelectedRows()) rows.add(files.row(fileTable.convertRowIndexToModel(i)));
        return rows;
    }

    /** What Restore acts on: a chosen version, else the chosen files, else the chosen folder. */
    private Optional<Selection> selection() {
        int v = versionTable.getSelectedRow();
        if (v >= 0) {
            Version version = versions.row(v);
            return Optional.of(new Selection(version.snapshotId(), List.of(version.entry())));
        }
        List<FileRow> rows = selectedRows();
        if (!rows.isEmpty()) {
            String snapshot = rows.getFirst().snapshotId();
            if (rows.stream().allMatch(r -> r.snapshotId().equals(snapshot))) {
                return Optional.of(new Selection(snapshot, rows.stream().map(FileRow::entry).toList()));
            }
            return Optional.empty();
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (current != null && node != null && node.getUserObject() instanceof Folder f) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            return Optional.of(new Selection(current.id(), List.of(new SnapshotEntry(name, f.path(),
                    SnapshotEntry.Type.DIRECTORY, 0, null))));
        }
        return Optional.empty();
    }

    record Selection(String snapshotId, List<SnapshotEntry> entries) {
    }

    private void updateButtons() {
        Optional<Selection> s = selection();
        restore.setEnabled(s.isPresent());
        restoreTo.setEnabled(s.isPresent());
        open.setEnabled(s.isPresent() && s.get().entries().size() == 1 && s.get().entries().getFirst().type()
                == SnapshotEntry.Type.FILE);
    }

    private void restoreSelected(boolean elsewhere) {
        Optional<Selection> s = selection();
        if (s.isEmpty()) return;
        Path destination = null;
        if (elsewhere) {
            JFileChooser chooser = new JFileChooser(xdg.home().toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(msg.get("restore.to.choose"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            destination = chooser.getSelectedFile().toPath();
        }
        run(s.get(), destination);
    }

    /** The whole backup back to where it was, e.g. after a new installation. */
    private void restoreAll() {
        if (current == null) return;
        if (JOptionPane.showConfirmDialog(this, html(msg.format("restore.all.question", formats.when(current.time(),
                clock.instant()), String.join(", ", current.paths()))), msg.get("restore.all.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<SnapshotEntry> entries = new ArrayList<>();
        for (String p : current.paths()) {
            entries.add(new SnapshotEntry(p.substring(p.lastIndexOf('/') + 1), p, SnapshotEntry.Type.DIRECTORY, 0, null));
        }
        run(new Selection(current.id(), entries), null);
    }

    private void run(Selection selection, Path destination) {
        Restorer.Conflicts conflicts = Restorer.Conflicts.KEEP_BOTH;
        List<Path> existing = Restorer.existing(selection.entries(), destination);
        if (!existing.isEmpty()) {
            Optional<Restorer.Conflicts> choice = askConflicts(existing);
            if (choice.isEmpty()) return;
            conflicts = choice.get();
        }
        Cancellation cancellation = new Cancellation();
        ProgressDialog progress = new ProgressDialog(this, formats, msg.get("restore.progress.title"),
                msg.format("restore.progress.heading", selection.entries().size()), cancellation);
        progress.setVisible(true);
        Restorer.Conflicts policy = conflicts;
        background.execute(() -> {
            try {
                Restorer.Result result = restorer.restore(repository, selection.snapshotId(), selection.entries(),
                        destination, policy, progress::update, cancellation);
                new EdtExecutor().execute(() -> {
                    progress.close();
                    listings.clear();
                    String text = msg.format("restore.done", result.restored(), result.skipped());
                    if (!result.errors().isEmpty()) text += "\n\n" + String.join("\n", result.errors().subList(0,
                            Math.min(10, result.errors().size())));
                    JOptionPane.showMessageDialog(this, html(text), getTitle(), result.errors().isEmpty()
                            ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                    if (current != null && !searching) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
                        if (node != null && node.getUserObject() instanceof Folder f) showFolder(f.path());
                    }
                });
            } catch (EngineException e) {
                new EdtExecutor().execute(progress::close);
                if (e.kind() != EngineException.Kind.CANCELLED) failed(e);
            }
        });
    }

    private Optional<Restorer.Conflicts> askConflicts(List<Path> existing) {
        String[] options = {msg.get("conflict.keep-both"), msg.get("conflict.replace"), msg.get("conflict.skip"),
                msg.get("dialog.cancel")};
        String names = existing.stream().limit(5).map(p -> p.getFileName().toString()).reduce((a, b) -> a + ", " + b)
                .orElse("") + (existing.size() > 5 ? ", …" : "");
        int answer = JOptionPane.showOptionDialog(this, html(msg.format("conflict.question", existing.size(), names)),
                msg.get("conflict.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                options[0]);
        return switch (answer) {
            case 0 -> Optional.of(Restorer.Conflicts.KEEP_BOTH);
            case 1 -> Optional.of(Restorer.Conflicts.REPLACE);
            case 2 -> Optional.of(Restorer.Conflicts.SKIP);
            default -> Optional.empty();
        };
    }

    /** Writes the file into the cache and opens it with its usual program, without touching the original. */
    private void openSelected() {
        Optional<Selection> s = selection();
        if (s.isEmpty() || s.get().entries().size() != 1 || s.get().entries().getFirst().type() != SnapshotEntry.Type.FILE) {
            return;
        }
        SnapshotEntry entry = s.get().entries().getFirst();
        Instant time = snapshotTimes.getOrDefault(s.get().snapshotId(), clock.instant());
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").format(time.atZone(formats.zone()));
        busy(true);
        background.execute(() -> {
            try {
                Path dir = xdg.cacheHome().resolve("amadeos/backup/open").resolve(stamp);
                Files.createDirectories(dir);
                Path file = dir.resolve(entry.name());
                engine.dump(repository, s.get().snapshotId(), entry.path(), file);
                new EdtExecutor().execute(() -> {
                    busy(false);
                    try {
                        launcher.open(file);
                    } catch (IOException e) {
                        JOptionPane.showMessageDialog(this, html(msg.format("restore.open.failed", e.getMessage())),
                                getTitle(), JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (EngineException | IOException e) {
                failed(e);
            }
        });
    }

    private void failed(Exception e) {
        new EdtExecutor().execute(() -> {
            busy(false);
            String text = e instanceof EngineException ee && ee.kind() != EngineException.Kind.FAILED
                    && msg.has("engine." + ee.kind().key()) ? msg.get("engine." + ee.kind().key()) : e.getMessage();
            JOptionPane.showMessageDialog(this, html(text), getTitle(), JOptionPane.ERROR_MESSAGE);
        });
    }

    private void busy(boolean busy) {
        loads += busy ? 1 : -1;
        if (loads < 0) loads = 0;
        setCursor(loads > 0 ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private static String html(String text) {
        return "<html><div style='width:380px'>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>") + "</div></html>";
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    private Component timelineCell(JList<? extends Timeline.Row> list, Timeline.Row row, int index, boolean sel,
                                   boolean focus) {
        JLabel l = new JLabel();
        var theme = AmadeUI.theme();
        if (row instanceof Timeline.Heading h) {
            l.setText(heading(h));
            l.setFont(l.getFont().deriveFont(Font.BOLD, AmadeUI.scaled(10f)));
            l.setForeground(theme.textInactive());
            l.setBorder(new EmptyBorder(6, 6, 2, 6));
        } else if (row instanceof Timeline.Entry e) {
            l.setText(entryLabel(e.snapshot()));
            l.setBorder(new EmptyBorder(2, 18, 2, 6));
            l.setOpaque(sel);
            if (sel) {
                l.setBackground(theme.selectionBackground());
                l.setForeground(theme.selectionText());
            }
        }
        return l;
    }

    String heading(Timeline.Heading h) {
        return switch (h.kind()) {
            case "month" -> h.month().getMonth().getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, msg.locale())
                    .toUpperCase(msg.locale());
            case "year" -> h.year().toString();
            default -> msg.get("timeline." + h.kind()).toUpperCase(msg.locale());
        };
    }

    private String entryLabel(Snapshot s) {
        var t = s.time().atZone(formats.zone());
        var today = clock.instant().atZone(formats.zone()).toLocalDate();
        if (t.toLocalDate().equals(today) || t.toLocalDate().equals(today.minusDays(1))) {
            return DateTimeFormatter.ofPattern("HH:mm", msg.locale()).format(t);
        }
        return DateTimeFormatter.ofPattern(msg.get("time.pattern.timeline"), msg.locale()).format(t);
    }

    private void configureFileColumns() {
        files.fireTableStructureChanged();
        int last = fileTable.getColumnCount() - 1;
        if (last < 0) return;
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        fileTable.getColumnModel().getColumn(1).setCellRenderer(right);
        fileTable.getColumnModel().getColumn(last).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (!sel) {
                    Comparison cmp = files.row(t.convertRowIndexToModel(r)).comparison();
                    setForeground(switch (cmp) {
                        case SAME -> AmadeUI.theme().textInactive();
                        case CHANGED -> CHANGED;
                        case DELETED -> DELETED;
                    });
                }
                return this;
            }
        });
    }

    private final class FileModel extends AbstractTableModel {
        private List<FileRow> rows = List.of();

        void set(List<FileRow> list) {
            rows = list;
            fireTableDataChanged();
        }

        FileRow row(int i) {
            return rows.get(i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return searching ? 5 : 4;
        }

        @Override
        public String getColumnName(int column) {
            String[] keys = searching ? new String[]{"name", "size", "modified", "backup", "compared"}
                    : new String[]{"name", "size", "modified", "compared"};
            return msg.get("restore.column." + keys[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            FileRow r = rows.get(row);
            SnapshotEntry e = r.entry();
            if (searching && column == 0) return e.path();
            if (column == 0) return e.name() + (e.isDirectory() ? "/" : "");
            if (column == 1) return e.isDirectory() ? "" : formats.bytes(e.size());
            if (column == 2) return e.modified() == null ? "" : formats.when(e.modified(), clock.instant());
            if (searching && column == 3) {
                Instant t = snapshotTimes.get(r.snapshotId());
                return t == null ? "" : formats.when(t, clock.instant());
            }
            return msg.get("compared." + r.comparison().key());
        }
    }

    private final class VersionModel extends AbstractTableModel {
        private List<Version> rows = List.of();

        void set(List<Version> list) {
            rows = list;
            fireTableDataChanged();
        }

        Version row(int i) {
            return rows.get(i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return msg.get("restore.version." + new String[]{"modified", "size", "backups"}[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Version v = rows.get(row);
            Instant now = clock.instant();
            return switch (column) {
                case 0 -> v.entry().modified() == null ? "" : formats.when(v.entry().modified(), now);
                case 1 -> formats.bytes(v.entry().size());
                default -> v.firstBackup().equals(v.lastBackup()) ? formats.when(v.firstBackup(), now)
                        : msg.format("restore.version.range", formats.when(v.firstBackup(), now),
                        formats.when(v.lastBackup(), now));
            };
        }
    }

    // ---- for tests -------------------------------------------------------------------------------------------------

    JList<Timeline.Row> timelineList() {
        return timelineList;
    }

    JTable fileTable() {
        return fileTable;
    }

    JTable versionTable() {
        return versionTable;
    }

    JTree folderTree() {
        return tree;
    }
}

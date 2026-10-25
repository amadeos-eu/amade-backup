package eu.amadeos.apps.backup.ui.edit;

import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.FolderPair;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.JobKind;
import eu.amadeos.apps.backup.domain.SyncOptions;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The settings of one job in tabs: what and where, what to leave out, when (and for backups which older
 * backups stay), and for syncs the commands before and after. The target of an existing backup is fixed: a
 * different place means a new repository, so a new job.
 */
public final class JobEditor extends JDialog {
    static final long LARGE = 4_000_000_000L;

    private final Messages msg;
    private final Job original;
    private final boolean isNew;
    private final Places places;
    private final Path home;
    private final JTextField name = new JTextField(30);
    private final JTextField target = new JTextField(36);
    private final DefaultListModel<String> sources = new DefaultListModel<>();
    private final Map<HomeArea, JCheckBox> areas = new EnumMap<>(HomeArea.class);
    private final DefaultTableModel pairs = new DefaultTableModel(new Object[0][], new String[2]) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable pairTable = new JTable(pairs);
    private final JComboBox<String> direction;
    private final JCheckBox ignoreExisting;
    private final JCheckBox sizeOnly;
    private final JCheckBox skipNewer;
    private final JTextArea patterns = new JTextArea(6, 36);
    private final JCheckBox caches;
    private final JCheckBox large;
    private final JCheckBox oneFileSystem;
    private final ScheduleKeepPanel scheduleKeep;
    private final JTextField before = new JTextField(36);
    private final JCheckBox stopIfBeforeFails;
    private final JTextField after = new JTextField(36);
    private Job result;

    public JobEditor(Window owner, Messages msg, Job job, boolean isNew, Places places, Path home) {
        super(owner, msg.format(isNew ? "editor.title.new." + job.kind().key() : "editor.title", job.name()),
                ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.original = job;
        this.isNew = isNew;
        this.places = places;
        this.home = home;
        setName("editor");
        name.setText(job.name());
        name.setName("editor.name");
        direction = new JComboBox<>(new String[]{msg.get("sync.direction.mirror"), msg.get("sync.direction.update")});
        direction.setSelectedIndex(job.sync().mirror() ? 0 : 1);
        direction.setName("editor.direction");
        ignoreExisting = Mnemonics.apply(new JCheckBox(), msg.get("sync.ignore-existing"));
        ignoreExisting.setSelected(job.sync().ignoreExisting());
        sizeOnly = Mnemonics.apply(new JCheckBox(), msg.get("sync.size-only"));
        sizeOnly.setSelected(job.sync().sizeOnly());
        skipNewer = Mnemonics.apply(new JCheckBox(), msg.get("sync.skip-newer"));
        skipNewer.setSelected(job.sync().skipNewer());
        caches = Mnemonics.apply(new JCheckBox(), msg.get("leave-out.caches"));
        caches.setSelected(job.options().excludeCaches());
        large = Mnemonics.apply(new JCheckBox(), msg.format("leave-out.large", "4 GB"));
        large.setSelected(job.options().maxFileSize() > 0);
        oneFileSystem = Mnemonics.apply(new JCheckBox(), msg.get("leave-out.one-file-system"));
        oneFileSystem.setSelected(job.options().oneFileSystem());
        scheduleKeep = new ScheduleKeepPanel(msg, job.schedule(), job.kind() == JobKind.BACKUP ? job.keep() : null);
        stopIfBeforeFails = Mnemonics.apply(new JCheckBox(), msg.get("sync.stop-if-before-fails"));
        stopIfBeforeFails.setSelected(job.sync().stopIfBeforeFails());
        before.setText(job.sync().before());
        after.setText(job.sync().after());
        patterns.setText(String.join("\n", job.excludes()));
        patterns.setName("editor.patterns");

        JTabbedPane tabs = new JTabbedPane();
        tabs.setName("editor.tabs");
        tabs.addTab(msg.get("editor.tab.general"), pad(job.kind() == JobKind.BACKUP ? backupTab(job) : syncTab(job)));
        tabs.addTab(msg.get("editor.tab.leave-out"), pad(leaveOutTab(job)));
        tabs.addTab(msg.get(job.kind() == JobKind.BACKUP ? "editor.tab.when-keep" : "editor.tab.when"), pad(scheduleKeep));
        if (job.kind() == JobKind.SYNC) tabs.addTab(msg.get("editor.tab.commands"), pad(commandsTab()));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton ok = new JButton(msg.get("dialog.ok"));
        ok.setName("editor.ok");
        ok.addActionListener(e -> accept());
        JButton cancel = new JButton(msg.get("dialog.cancel"));
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(tabs, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(720)), Math.round(AmadeUI.scaled(520))));
        pack();
        setLocationRelativeTo(owner);
    }

    public Optional<Job> showAndWait() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    private static JComponent pad(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(c, BorderLayout.NORTH);
        return p;
    }

    private JComponent backupTab(Job job) {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        y = row(form, g, y, "editor.name", name);
        target.setName("editor.target");
        target.setText(job.target().onRemovableDisk()
                ? job.target().diskLabel() + ": " + job.target().path() : job.target().path().toString());
        target.setEditable(isNew);
        JPanel targetRow = new JPanel(new BorderLayout(6, 0));
        targetRow.add(target, BorderLayout.CENTER);
        if (isNew) {
            JButton choose = Mnemonics.apply(new JButton(), msg.get("editor.choose"));
            choose.addActionListener(e -> chooseFolder(target, true));
            targetRow.add(choose, BorderLayout.EAST);
        }
        y = row(form, g, y, "editor.target", targetRow);
        if (!isNew) {
            JLabel fixed = new JLabel(msg.get("editor.target.fixed"));
            fixed.setForeground(AmadeUI.theme().textInactive());
            y = row(form, g, y, null, fixed);
        }
        if (job.isHome()) {
            JPanel boxes = new JPanel(new GridLayout(0, 2, 8, 0));
            for (HomeArea area : HomeArea.values()) {
                JCheckBox box = new JCheckBox(msg.get("area." + area.key()), job.homeAreas().contains(area));
                box.setName("area." + area.key());
                areas.put(area, box);
                boxes.add(box);
            }
            boxes.setBorder(new TitledBorder(msg.format("editor.home", home)));
            g.gridx = 0;
            g.gridy = y++;
            g.gridwidth = 2;
            form.add(boxes, g);
            g.gridwidth = 1;
        } else {
            job.sources().forEach(p -> sources.addElement(p.toString()));
            JList<String> list = new JList<>(sources);
            list.setName("editor.sources");
            list.setVisibleRowCount(6);
            JPanel panel = new JPanel(new BorderLayout(6, 0));
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel b = new JPanel(new GridLayout(2, 1, 0, 4));
            JButton add = Mnemonics.apply(new JButton(), msg.get("editor.add"));
            add.addActionListener(e -> {
                Optional<Path> p = choose(home, false);
                p.ifPresent(path -> sources.addElement(path.toString()));
            });
            JButton remove = Mnemonics.apply(new JButton(), msg.get("editor.remove"));
            remove.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i >= 0) sources.remove(i);
            });
            b.add(add);
            b.add(remove);
            JPanel bw = new JPanel(new BorderLayout());
            bw.add(b, BorderLayout.NORTH);
            panel.add(bw, BorderLayout.EAST);
            row(form, g, y, "editor.sources", panel);
        }
        return form;
    }

    private JComponent syncTab(Job job) {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = row(form, g, 0, "editor.name", name);
        pairs.setColumnIdentifiers(new String[]{msg.get("editor.pair.from"), msg.get("editor.pair.to")});
        for (FolderPair p : job.pairs()) pairs.addRow(new Object[]{p.source().toString(), p.target().toString()});
        pairTable.setName("editor.pairs");
        pairTable.setRowHeight(Math.round(AmadeUI.scaled(20)));
        JScrollPane scroll = new JScrollPane(pairTable);
        scroll.setPreferredSize(new Dimension(520, 110));
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel b = new JPanel(new GridLayout(2, 1, 0, 4));
        JButton add = Mnemonics.apply(new JButton(), msg.get("editor.add"));
        add.setName("editor.add-pair");
        add.addActionListener(e -> addPair());
        JButton remove = Mnemonics.apply(new JButton(), msg.get("editor.remove"));
        remove.addActionListener(e -> {
            int i = pairTable.getSelectedRow();
            if (i >= 0) pairs.removeRow(i);
        });
        b.add(add);
        b.add(remove);
        JPanel bw = new JPanel(new BorderLayout());
        bw.add(b, BorderLayout.NORTH);
        panel.add(bw, BorderLayout.EAST);
        y = row(form, g, y, "editor.pairs", panel);
        y = row(form, g, y, "editor.direction", direction);
        JPanel options = new JPanel(new GridLayout(3, 1));
        options.add(ignoreExisting);
        options.add(sizeOnly);
        options.add(skipNewer);
        y = row(form, g, y, "editor.compare", options);
        JLabel note = new JLabel("<html><div style='width:460px'>" + msg.get("editor.sync.note") + "</div></html>");
        note.setForeground(AmadeUI.theme().textInactive());
        row(form, g, y, null, note);
        return form;
    }

    private void addPair() {
        Optional<Path> from = choose(home, false);
        if (from.isEmpty()) return;
        Path start = Path.of("/media", System.getProperty("user.name", ""));
        Optional<Path> to = choose(java.nio.file.Files.isDirectory(start) ? start : home, false);
        to.ifPresent(t -> pairs.addRow(new Object[]{from.get().toString(), t.toString()}));
    }

    private JComponent leaveOutTab(Job job) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JLabel intro = new JLabel("<html><div style='width:520px'>" + msg.get("editor.leave-out.intro") + "</div></html>");
        panel.add(intro, BorderLayout.NORTH);
        panel.add(new JScrollPane(patterns), BorderLayout.CENTER);
        if (job.kind() == JobKind.BACKUP) {
            JPanel options = new JPanel(new GridLayout(3, 1));
            options.add(caches);
            options.add(large);
            options.add(oneFileSystem);
            panel.add(options, BorderLayout.SOUTH);
        }
        return panel;
    }

    private JComponent commandsTab() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        before.setName("editor.before");
        after.setName("editor.after");
        int y = row(form, g, 0, "editor.before", before);
        y = row(form, g, y, null, stopIfBeforeFails);
        y = row(form, g, y, "editor.after", after);
        JLabel note = new JLabel("<html><div style='width:460px'>" + msg.get("editor.commands.note") + "</div></html>");
        note.setForeground(AmadeUI.theme().textInactive());
        row(form, g, y, null, note);
        return form;
    }

    private int row(JPanel form, GridBagConstraints g, int y, String key, JComponent field) {
        g.gridy = y;
        g.gridx = 0;
        g.weightx = 0;
        if (key != null) {
            JLabel label = Mnemonics.apply(new JLabel(), msg.get(key));
            label.setLabelFor(field);
            form.add(label, g);
        }
        g.gridx = 1;
        g.weightx = 1;
        form.add(field, g);
        return y + 1;
    }

    private void chooseFolder(JTextField field, boolean media) {
        Path start = media ? Path.of("/media", System.getProperty("user.name", "")) : home;
        choose(java.nio.file.Files.isDirectory(start) ? start : home, false).ifPresent(p -> field.setText(p.toString()));
    }

    private Optional<Path> choose(Path start, boolean files) {
        JFileChooser chooser = new JFileChooser(start.toFile());
        chooser.setFileSelectionMode(files ? JFileChooser.FILES_AND_DIRECTORIES : JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return Optional.empty();
        return Optional.of(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
    }

    /** The edited job, or the reason it cannot be saved yet. */
    String problem() {
        if (name.getText().isBlank()) return msg.get("editor.problem.name");
        if (original.kind() == JobKind.BACKUP) {
            if (!original.isHome() && sources.isEmpty()) return msg.get("editor.problem.sources");
            if (original.isHome() && areas.values().stream().noneMatch(JCheckBox::isSelected)) {
                return msg.get("editor.problem.areas");
            }
            if (isNew && !Path.of(target.getText().strip()).isAbsolute()) return msg.get("editor.problem.target");
        } else if (pairs.getRowCount() == 0) {
            return msg.get("editor.problem.pairs");
        }
        return null;
    }

    Job edited() {
        Job job = original.withName(name.getText().strip())
                .withExcludes(Arrays.stream(patterns.getText().split("\n")).map(String::strip).filter(s -> !s.isEmpty())
                        .toList())
                .withSchedule(scheduleKeep.schedule());
        if (original.kind() == JobKind.BACKUP) {
            job = job.withKeep(scheduleKeep.keep()).withOptions(new BackupOptions(caches.isSelected(),
                    oneFileSystem.isSelected(), large.isSelected() ? LARGE : 0));
            if (original.isHome()) {
                Set<HomeArea> chosen = EnumSet.noneOf(HomeArea.class);
                areas.forEach((a, b) -> {
                    if (b.isSelected()) chosen.add(a);
                });
                job = job.withHomeAreas(chosen);
            } else {
                List<Path> list = new ArrayList<>();
                for (int i = 0; i < sources.size(); i++) list.add(Path.of(sources.get(i)));
                job = job.withSources(list);
            }
            if (isNew) {
                Path folder = Path.of(target.getText().strip()).toAbsolutePath().normalize();
                if (!folder.getFileName().toString().equals(original.id())) {
                    folder = (folder.getFileName().toString().equals(Places.FOLDER) ? folder
                            : folder.resolve(Places.FOLDER)).resolve(original.id());
                }
                Target t = places.targetFor(folder);
                job = job.withTarget(t);
            }
        } else {
            List<FolderPair> list = new ArrayList<>();
            for (int i = 0; i < pairs.getRowCount(); i++) {
                list.add(new FolderPair(Path.of((String) pairs.getValueAt(i, 0)), Path.of((String) pairs.getValueAt(i, 1))));
            }
            job = job.withPairs(list).withSync(new SyncOptions(direction.getSelectedIndex() == 0,
                    ignoreExisting.isSelected(), sizeOnly.isSelected(), skipNewer.isSelected(), before.getText(),
                    stopIfBeforeFails.isSelected(), after.getText()));
        }
        return job;
    }

    private void accept() {
        String problem = problem();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        result = edited();
        dispose();
    }

    // ---- for tests ---------------------------------------------------------------------------------------------

    JTextField nameField() {
        return name;
    }

    DefaultTableModel pairModel() {
        return pairs;
    }

    JTextArea patternArea() {
        return patterns;
    }
}

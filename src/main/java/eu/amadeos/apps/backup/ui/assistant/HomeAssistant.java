package eu.amadeos.apps.backup.ui.assistant;

import eu.amadeos.apps.backup.disk.Disk;
import eu.amadeos.apps.backup.disk.Disks;
import eu.amadeos.apps.backup.domain.BackupOptions;
import eu.amadeos.apps.backup.domain.HomeArea;
import eu.amadeos.apps.backup.domain.HomeLayout;
import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.domain.Target;
import eu.amadeos.apps.backup.repository.JobStore;
import eu.amadeos.apps.backup.run.Passwords;
import eu.amadeos.apps.backup.run.Places;
import eu.amadeos.apps.backup.sync.FileSystemInfo;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.apps.backup.ui.common.PasswordPanel;
import eu.amadeos.apps.backup.ui.common.RecoverySheet;
import eu.amadeos.apps.backup.ui.edit.ScheduleKeepPanel;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.print.PrinterException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * "Back Up the Home Folder" in five steps: where to (a plugged-in disk or a folder), what (the parts of the
 * home folder with their sizes), when and how long, the password, and the summary with the first backup.
 */
public final class HomeAssistant extends JDialog {
    /** The new job, its password and whether to start the first backup at once. */
    public record Result(Job job, String password, boolean startNow) {
    }

    public static final String[] STEPS = {"where", "what", "when", "password", "start"};
    /** Files larger than this are left out when the box is ticked: disk images and the like. */
    static final long LARGE = 4_000_000_000L;

    private final Messages msg;
    private final Formats formats;
    private final HomeLayout layout;
    private final Disks disks;
    private final Places places;
    private final Passwords passwords;
    private final List<Job> existing;
    private final ExecutorService background;
    private final Clock clock;
    private final String jobId;

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final JLabel[] stepLabels = new JLabel[STEPS.length];
    private final JButton back;
    private final JButton next;
    private int step;
    private Result result;

    // step 1
    private final JPanel diskList = new JPanel();
    private final ButtonGroup diskGroup = new ButtonGroup();
    private final List<JRadioButton> diskButtons = new ArrayList<>();
    private final List<Disk> diskChoices = new ArrayList<>();
    private final JRadioButton folderChoice;
    private final JTextField folder = new JTextField(30);
    // step 2
    private final Map<HomeArea, JCheckBox> areas = new EnumMap<>(HomeArea.class);
    private final Map<HomeArea, JLabel> sizes = new EnumMap<>(HomeArea.class);
    private final JLabel leftOutSize = new JLabel("…", SwingConstants.RIGHT);
    private final JLabel selectedSum = new JLabel(" ");
    private final JTextArea patterns = new JTextArea(4, 30);
    private final JCheckBox caches;
    private final JCheckBox large;
    private final JCheckBox oneFileSystem;
    private HomeLayout.Sizes measured;
    private volatile boolean closed;
    // step 3
    private final ScheduleKeepPanel scheduleKeep;
    // step 4
    private final PasswordPanel password;
    // step 5
    private final JLabel summary = new JLabel();
    private final JCheckBox startNow;

    public HomeAssistant(Window owner, Messages msg, Formats formats, HomeLayout layout, Disks disks, Places places,
                         Passwords passwords, List<Job> existing, ExecutorService background, Clock clock) {
        super(owner, msg.get("assistant.title"), ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.formats = formats;
        this.layout = layout;
        this.disks = disks;
        this.places = places;
        this.passwords = passwords;
        this.existing = existing;
        this.background = background;
        this.clock = clock;
        this.jobId = JobStore.newId(msg.get("assistant.job-name"), existing);
        setName("assistant");

        folderChoice = Mnemonics.apply(new JRadioButton(), msg.get("assistant.where.folder"));
        folderChoice.setName("assistant.where.folder");
        caches = Mnemonics.apply(new JCheckBox(), msg.get("leave-out.caches"));
        caches.setSelected(true);
        large = Mnemonics.apply(new JCheckBox(), msg.format("leave-out.large", formats.bytes(LARGE)));
        oneFileSystem = Mnemonics.apply(new JCheckBox(), msg.get("leave-out.one-file-system"));
        oneFileSystem.setSelected(true);
        scheduleKeep = new ScheduleKeepPanel(msg, Schedule.DEFAULT, KeepPolicy.DEFAULT);
        password = new PasswordPanel(msg, passwords::generate, this::printSheet);
        password.onChange(this::updateButtons);
        startNow = Mnemonics.apply(new JCheckBox(), msg.get("assistant.start.now"));
        startNow.setSelected(true);
        startNow.setName("assistant.start.now");

        pages.add(page("where", wherePage()), STEPS[0]);
        pages.add(page("what", whatPage()), STEPS[1]);
        pages.add(page("when", scheduleKeep), STEPS[2]);
        pages.add(page("password", password), STEPS[3]);
        pages.add(page("start", startPage()), STEPS[4]);

        back = Mnemonics.apply(new JButton(), msg.get("assistant.back"));
        back.setName("assistant.back");
        back.addActionListener(e -> go(step - 1));
        next = Mnemonics.apply(new JButton(), msg.get("assistant.next"));
        next.setName("assistant.next");
        next.addActionListener(e -> {
            if (step == STEPS.length - 1) finish(); else go(step + 1);
        });
        JButton cancel = new JButton(msg.get("dialog.cancel"));
        cancel.setName("assistant.cancel");
        cancel.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout(14, 0));
        root.setBorder(new EmptyBorder(12, 12, 10, 12));
        root.add(stepsPanel(), BorderLayout.WEST);
        root.add(pages, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(10, 0, 0, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.add(cancel);
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(next);
        refreshDisks();
        measure();
        go(0);
        setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(860)), Math.round(AmadeUI.scaled(580))));
        pack();
        setLocationRelativeTo(owner);
    }

    public Optional<Result> showAndWait() {
        setVisible(true);
        return Optional.ofNullable(result);
    }

    private JComponent stepsPanel() {
        JPanel steps = new JPanel(new GridLayout(STEPS.length, 1, 0, 2));
        steps.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), new EmptyBorder(8, 10, 8, 10)));
        steps.setBackground(AmadeUI.theme().fieldBackground());
        for (int i = 0; i < STEPS.length; i++) {
            stepLabels[i] = new JLabel();
            steps.add(stepLabels[i]);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(steps, BorderLayout.NORTH);
        wrap.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(200)), 10));
        return wrap;
    }

    private JComponent page(String key, JComponent body) {
        JPanel page = new JPanel(new BorderLayout(0, 8));
        JLabel heading = new JLabel(msg.get("assistant." + key + ".heading"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, AmadeUI.scaled(16f)));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(heading, BorderLayout.NORTH);
        top.add(new JLabel("<html><div style='width:440px'>" + msg.get("assistant." + key + ".intro") + "</div></html>"),
                BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    // ---- step 1: where -----------------------------------------------------------------------------------------

    private JComponent wherePage() {
        diskList.setLayout(new BoxLayout(diskList, BoxLayout.Y_AXIS));
        diskList.setBackground(AmadeUI.theme().fieldBackground());
        diskList.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(diskList);
        scroll.setPreferredSize(new Dimension(500, 180));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(6, 6));
        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folderChoice, BorderLayout.WEST);
        folder.setName("assistant.where.path");
        folder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { folderChoice.setSelected(true); updateButtons(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateButtons(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateButtons(); }
        });
        folderRow.add(folder, BorderLayout.CENTER);
        JButton choose = Mnemonics.apply(new JButton(), msg.get("assistant.where.choose"));
        choose.addActionListener(e -> chooseFolder());
        folderRow.add(choose, BorderLayout.EAST);
        south.add(folderRow, BorderLayout.NORTH);
        JPanel refreshRow = new JPanel(new BorderLayout());
        JLabel hint = new JLabel("<html><div style='width:420px'>" + msg.get("assistant.where.hint") + "</div></html>");
        hint.setForeground(AmadeUI.theme().textInactive());
        refreshRow.add(hint, BorderLayout.CENTER);
        JButton refresh = Mnemonics.apply(new JButton(), msg.get("assistant.where.refresh"));
        refresh.setName("assistant.where.refresh");
        refresh.addActionListener(e -> refreshDisks());
        JPanel r = new JPanel(new BorderLayout());
        r.add(refresh, BorderLayout.NORTH);
        refreshRow.add(r, BorderLayout.EAST);
        south.add(refreshRow, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        diskGroup.add(folderChoice);
        folderChoice.addActionListener(e -> updateButtons());
        return panel;
    }

    void refreshDisks() {
        diskList.removeAll();
        diskButtons.forEach(diskGroup::remove);
        diskButtons.clear();
        diskChoices.clear();
        List<Disk> mounted = disks.mounted();
        if (mounted.isEmpty()) {
            JLabel none = new JLabel(msg.get("assistant.where.none"));
            none.setForeground(AmadeUI.theme().textInactive());
            diskList.add(none);
        }
        for (Disk disk : mounted) {
            String text = "<html><b>" + escape(disk.label()) + "</b>&nbsp;&nbsp;" + escape(msg.format("assistant.where.disk",
                    disk.type(), formats.bytes(disk.usable()), formats.bytes(disk.total()), disk.mountPoint())) + "</html>";
            JRadioButton b = new JRadioButton(text);
            b.setOpaque(false);
            b.setName("disk." + diskButtons.size());
            b.addActionListener(e -> updateButtons());
            diskGroup.add(b);
            diskButtons.add(b);
            diskChoices.add(disk);
            diskList.add(b);
            if (FileSystemInfo.isWindowsType(disk.type())) {
                JLabel note = new JLabel("      " + msg.get("assistant.where.windows-type"));
                note.setForeground(AmadeUI.theme().textInactive());
                diskList.add(note);
            }
        }
        if (!diskButtons.isEmpty() && !folderChoice.isSelected()) diskButtons.getFirst().setSelected(true);
        diskList.add(Box.createVerticalGlue());
        diskList.revalidate();
        diskList.repaint();
        updateButtons();
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(folder.getText().isBlank() ? layout.home().toFile()
                : Path.of(folder.getText()).toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folder.setText(chooser.getSelectedFile().getAbsolutePath());
            folderChoice.setSelected(true);
            updateButtons();
        }
    }

    /** The chosen place, as the job's target: {@code <disk>/amadeos-backup/<job>}. */
    Optional<Target> target() {
        for (int i = 0; i < diskButtons.size(); i++) {
            if (diskButtons.get(i).isSelected()) {
                Disk d = diskChoices.get(i);
                Path relative = Path.of(Places.FOLDER, jobId);
                return Optional.of(d.uuid().isEmpty() ? Target.folder(d.mountPoint().resolve(relative))
                        : Target.onDisk(d.uuid(), d.label(), relative));
            }
        }
        if (folderChoice.isSelected() && !folder.getText().isBlank()) {
            Path chosen = Path.of(folder.getText().strip()).toAbsolutePath().normalize();
            return Optional.of(places.targetFor(chosen.resolve(Places.FOLDER).resolve(jobId)));
        }
        return Optional.empty();
    }

    private String targetText(Target target) {
        return target.onRemovableDisk() ? msg.format("target.disk", target.diskLabel()) + " · " + target.path()
                : target.path().toString();
    }

    // ---- step 2: what ------------------------------------------------------------------------------------------

    private JComponent whatPage() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(AmadeUI.theme().fieldBackground());
        grid.setBorder(BorderFactory.createCompoundBorder(AmadeUI.sunken(), new EmptyBorder(6, 8, 6, 8)));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(1, 4, 1, 8);
        g.anchor = GridBagConstraints.WEST;
        int y = 0;
        for (HomeArea area : HomeArea.values()) {
            g.gridy = y++;
            g.gridx = 0;
            g.weightx = 0;
            JCheckBox box = new JCheckBox(msg.get("area." + area.key()), area.byDefault());
            box.setName("area." + area.key());
            box.setOpaque(false);
            box.addActionListener(e -> updateSum());
            areas.put(area, box);
            grid.add(box, g);
            g.gridx = 1;
            JLabel size = new JLabel("…", SwingConstants.RIGHT);
            size.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(72)), size.getPreferredSize().height));
            sizes.put(area, size);
            grid.add(size, g);
            g.gridx = 2;
            g.weightx = 1;
            JLabel note = new JLabel(msg.get("area." + area.key() + ".note"));
            note.setForeground(AmadeUI.theme().textInactive());
            grid.add(note, g);
        }
        g.gridy = y;
        g.gridx = 0;
        g.weightx = 0;
        JCheckBox out = new JCheckBox(msg.get("area.left-out"), false);
        out.setEnabled(false);
        out.setOpaque(false);
        grid.add(out, g);
        g.gridx = 1;
        leftOutSize.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(72)), leftOutSize.getPreferredSize().height));
        grid.add(leftOutSize, g);
        g.gridx = 2;
        JLabel note = new JLabel(msg.get("area.left-out.note"));
        note.setForeground(AmadeUI.theme().textInactive());
        grid.add(note, g);

        selectedSum.setFont(selectedSum.getFont().deriveFont(Font.BOLD));
        selectedSum.setName("assistant.what.sum");
        JPanel leaveOut = new JPanel(new BorderLayout(6, 4));
        leaveOut.setBorder(new TitledBorder(msg.get("leave-out.title")));
        patterns.setText("*.iso\n**/node_modules\n");
        patterns.setName("assistant.what.patterns");
        leaveOut.add(new JScrollPane(patterns), BorderLayout.CENTER);
        JPanel options = new JPanel(new GridLayout(3, 1));
        options.add(caches);
        options.add(large);
        options.add(oneFileSystem);
        leaveOut.add(options, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(grid, BorderLayout.NORTH);
        panel.add(selectedSum, BorderLayout.CENTER);
        panel.add(leaveOut, BorderLayout.SOUTH);
        return panel;
    }

    /** Counts the home folder in the background; the sizes appear when done. */
    private void measure() {
        background.execute(() -> {
            try {
                HomeLayout.Sizes s = layout.measure(() -> closed);
                new EdtExecutor().execute(() -> {
                    measured = s;
                    for (HomeArea area : HomeArea.values()) sizes.get(area).setText(formats.bytes(s.bytes().getOrDefault(area, 0L)));
                    leftOutSize.setText(formats.bytes(s.leftOutBytes()));
                    updateSum();
                });
            } catch (java.io.IOException e) {
                new EdtExecutor().execute(() -> selectedSum.setText(msg.format("assistant.what.unreadable", e.getMessage())));
            }
        });
    }

    Set<HomeArea> selectedAreas() {
        Set<HomeArea> set = EnumSet.noneOf(HomeArea.class);
        areas.forEach((area, box) -> {
            if (box.isSelected()) set.add(area);
        });
        return set;
    }

    private void updateSum() {
        if (measured == null) {
            selectedSum.setText(msg.get("assistant.what.counting"));
        } else {
            Set<HomeArea> chosen = selectedAreas();
            selectedSum.setText(msg.format("assistant.what.sum", formats.bytes(measured.bytes(chosen)),
                    formats.count(measured.files(chosen))));
        }
        updateButtons();
    }

    List<String> patterns() {
        return Arrays.stream(patterns.getText().split("\n")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ---- step 5: start ------------------------------------------------------------------------------------------

    private JComponent startPage() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        summary.setVerticalAlignment(SwingConstants.TOP);
        summary.setName("assistant.summary");
        panel.add(summary, BorderLayout.CENTER);
        panel.add(startNow, BorderLayout.SOUTH);
        return panel;
    }

    private void updateSummary() {
        Job job = job();
        StringBuilder sb = new StringBuilder("<html><table cellpadding='3'>");
        row(sb, msg.get("fact.where"), target().map(this::targetText).orElse(""));
        String what = msg.format("fact.what.home-all", layout.home());
        List<String> off = new ArrayList<>();
        for (HomeArea a : HomeArea.values()) if (!job.homeAreas().contains(a)) off.add(msg.get("area." + a.key()));
        if (!off.isEmpty()) what = msg.format("fact.what.home-without", layout.home(), String.join(", ", off));
        row(sb, msg.get("fact.what"), what);
        row(sb, msg.get("fact.when"), scheduleKeep.describeSchedule());
        row(sb, msg.get("fact.keep"), scheduleKeep.describeKeep());
        row(sb, msg.get("fact.password"), msg.get(passwords.persistent() ? "fact.password.keyring" : "fact.password.session"));
        sb.append("</table><br>");
        if (measured != null) {
            long bytes = measured.bytes(job.homeAreas());
            // About 40 MB/s to a USB 3 disk for a first backup with encryption and compression.
            long minutes = Math.max(1, bytes / 40_000_000L / 60);
            sb.append(escape(msg.format("assistant.start.estimate", formats.bytes(bytes), minutes)));
        }
        sb.append("</html>");
        summary.setText(sb.toString());
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("<tr><td valign='top'><b>").append(escape(key)).append("</b></td><td>").append(escape(value))
                .append("</td></tr>");
    }

    // ---- navigation ---------------------------------------------------------------------------------------------

    void go(int target) {
        if (target < 0 || target >= STEPS.length) return;
        step = target;
        if (step == STEPS.length - 1) updateSummary();
        cards.show(pages, STEPS[step]);
        for (int i = 0; i < STEPS.length; i++) {
            String mark = i < step ? "✓  " : i == step ? "▶  " : "     ";
            stepLabels[i].setText(mark + (i + 1) + "  " + msg.get("assistant." + STEPS[i] + ".step"));
            stepLabels[i].setFont(stepLabels[i].getFont().deriveFont(i == step ? Font.BOLD : Font.PLAIN));
            stepLabels[i].setForeground(i > step ? AmadeUI.theme().textInactive() : AmadeUI.theme().fieldText());
        }
        updateButtons();
    }

    private void updateButtons() {
        if (back == null) return;
        back.setEnabled(step > 0);
        boolean ok = switch (step) {
            case 0 -> target().isPresent();
            case 1 -> !selectedAreas().isEmpty();
            case 3 -> password.complete();
            default -> true;
        };
        next.setEnabled(ok);
        Mnemonics.apply(next, msg.get(step == STEPS.length - 1 ? "assistant.finish" : "assistant.next"));
    }

    Job job() {
        Target target = target().orElse(Target.folder(layout.home().resolve(Places.FOLDER)));
        BackupOptions options = new BackupOptions(caches.isSelected(), oneFileSystem.isSelected(),
                large.isSelected() ? LARGE : 0);
        return Job.home(jobId, msg.get("assistant.job-name"), layout.home(), target, selectedAreas(), patterns())
                .withOptions(options).withSchedule(scheduleKeep.schedule()).withKeep(scheduleKeep.keep());
    }

    private void finish() {
        result = new Result(job(), password.password(), startNow.isSelected());
        dispose();
    }

    private void printSheet(String pw) {
        Job job = job();
        try {
            new RecoverySheet(msg, formats, job, target().map(this::targetText).orElse(""), pw, clock.instant()).print();
        } catch (PrinterException e) {
            JOptionPane.showMessageDialog(this, msg.format("error.print", e.getMessage()), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- for tests ---------------------------------------------------------------------------------------------

    int step() {
        return step;
    }

    JButton nextButton() {
        return next;
    }

    PasswordPanel passwordPanel() {
        return password;
    }

    Map<HomeArea, JCheckBox> areaBoxes() {
        return areas;
    }

    JTextField folderField() {
        return folder;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void dispose() {
        closed = true;
        super.dispose();
    }
}

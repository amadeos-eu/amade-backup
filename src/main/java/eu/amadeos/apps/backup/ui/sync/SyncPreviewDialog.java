package eu.amadeos.apps.backup.ui.sync;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.apps.backup.sync.SyncAction;
import eu.amadeos.apps.backup.sync.SyncPlan;
import eu.amadeos.apps.backup.ui.common.Formats;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.swing.AmadeUI;
import eu.amadeos.commons.swing.EdtExecutor;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * The preview of a sync, EuroSync's dry run as the normal view: every copy, update, removal and left-out
 * entry with its reason, the totals and the free space. Nothing happens until Sync Now.
 */
public final class SyncPreviewDialog extends JDialog {
    static final Color COPY = new Color(0x3F8A4A);
    static final Color UPDATE = new Color(0x2E5C9A);
    static final Color DELETE = new Color(0xA94442);

    private record Row(SyncAction action, String path) {
    }

    private final Messages msg;
    private final Formats formats;
    private final List<Row> rows = new ArrayList<>();
    private final JButton start;
    private boolean confirmed;

    public SyncPreviewDialog(Window owner, Messages msg, Formats formats, Job job, List<SyncPlan> plans) {
        super(owner, msg.format("preview.title", job.name()), ModalityType.APPLICATION_MODAL);
        EdtExecutor.requireEdt();
        this.msg = msg;
        this.formats = formats;
        setName("preview");
        boolean several = plans.size() > 1;
        for (SyncPlan plan : plans) {
            String prefix = several ? plan.pair().source().getFileName() + "/" : "";
            for (SyncAction a : plan.actions()) rows.add(new Row(a, prefix + a.path()));
        }
        JTable table = new JTable(new Model());
        table.setName("preview.table");
        table.setRowHeight(Math.round(AmadeUI.scaled(20)));
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(Math.round(AmadeUI.scaled(90)));
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                setFont(getFont().deriveFont(Font.BOLD, AmadeUI.scaled(10f)));
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!sel) {
                    setForeground(switch (rows.get(r).action().kind()) {
                        case MKDIR, COPY -> COPY;
                        case UPDATE -> UPDATE;
                        case DELETE -> DELETE;
                        case SKIP -> AmadeUI.theme().textInactive();
                    });
                }
                return this;
            }
        });
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(Math.round(AmadeUI.scaled(800)), Math.round(AmadeUI.scaled(300))));

        JPanel folders = new JPanel(new BorderLayout(0, 2));
        folders.setBorder(new TitledBorder(msg.get("preview.folders")));
        StringBuilder sb = new StringBuilder("<html>");
        for (SyncPlan plan : plans) {
            sb.append(escape(plan.pair().source().toString())).append("  →  ").append(escape(plan.pair().target().toString()))
                    .append("<br>");
        }
        sb.append(escape(msg.get(job.sync().mirror() ? "detail.sync.mirror" : "detail.sync.update"))).append("</html>");
        folders.add(new JLabel(sb.toString()), BorderLayout.CENTER);

        long copies = plans.stream().mapToLong(p -> p.count(SyncAction.Kind.COPY) + p.count(SyncAction.Kind.MKDIR)).sum();
        long updates = plans.stream().mapToLong(p -> p.count(SyncAction.Kind.UPDATE)).sum();
        long deletes = plans.stream().mapToLong(p -> p.count(SyncAction.Kind.DELETE)).sum();
        long skips = plans.stream().mapToLong(p -> p.count(SyncAction.Kind.SKIP)).sum();
        long bytes = plans.stream().mapToLong(SyncPlan::bytesToCopy).sum();
        boolean changes = plans.stream().anyMatch(SyncPlan::changesAnything);
        boolean tooLarge = plans.stream().anyMatch(SyncPlan::tooLarge);
        long free = plans.isEmpty() ? 0 : plans.getFirst().usableSpace();
        JLabel summary = new JLabel(changes ? msg.format("preview.summary", formats.count(copies), formats.bytes(bytes),
                formats.count(updates), formats.count(deletes), formats.count(skips), formats.bytes(free))
                : msg.get("preview.nothing"));
        summary.setName("preview.summary");
        JLabel note = new JLabel(tooLarge ? msg.get("preview.too-large") : deletes > 0 ? msg.get("preview.trash") : " ");
        note.setForeground(tooLarge ? DELETE : AmadeUI.theme().textInactive());

        JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
        previewPanel.setBorder(new TitledBorder(msg.get("preview.heading")));
        previewPanel.add(scroll, BorderLayout.CENTER);
        JPanel sums = new JPanel(new BorderLayout());
        sums.add(summary, BorderLayout.WEST);
        sums.add(note, BorderLayout.EAST);
        previewPanel.add(sums, BorderLayout.SOUTH);

        start = Mnemonics.apply(new JButton(), msg.get("preview.start"));
        start.setName("preview.start");
        start.setFont(start.getFont().deriveFont(Font.BOLD));
        start.setEnabled(changes && !tooLarge);
        start.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        JButton cancel = new JButton(msg.get(changes ? "dialog.cancel" : "dialog.close"));
        cancel.setName("preview.cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(start);
        buttons.add(cancel);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(folders, BorderLayout.NORTH);
        root.add(previewPanel, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(changes && !tooLarge ? start : cancel);
        pack();
        setLocationRelativeTo(owner);
    }

    /** True if the user started the sync. */
    public boolean showAndWait() {
        setVisible(true);
        return confirmed;
    }

    JButton startButton() {
        return start;
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return msg.get("preview.column." + new String[]{"action", "path", "size", "why"}[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            SyncAction a = rows.get(row).action();
            return switch (column) {
                case 0 -> msg.get("action." + (a.kind() == SyncAction.Kind.MKDIR ? "copy" : a.kind().key()));
                case 1 -> rows.get(row).path() + (a.directory() ? "/" : "");
                case 2 -> a.size() > 0 ? formats.bytes(a.size()) : "";
                default -> msg.get("why." + (a.kind() == SyncAction.Kind.MKDIR && a.reason() == SyncAction.Reason.NEW
                        ? "new-folder" : a.reason().key()));
            };
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

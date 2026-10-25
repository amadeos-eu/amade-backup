package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The password of a new backup: typed twice or generated, shown on request, with a recovery sheet to print and
 * a check box that the user has kept it somewhere, because without it nobody can read the backup.
 */
public final class PasswordPanel extends JPanel {
    public static final int MIN_LENGTH = 8;

    private final Messages msg;
    private final JPasswordField first = new JPasswordField(28);
    private final JPasswordField second = new JPasswordField(28);
    private final JCheckBox show;
    private final JCheckBox kept;
    private final JLabel problem = new JLabel(" ");
    private final char echo;
    private Runnable changed = () -> { };

    public PasswordPanel(Messages msg, Supplier<String> generator, Consumer<String> printSheet) {
        super(new BorderLayout(0, 8));
        this.msg = msg;
        echo = first.getEchoChar();
        first.setName("password.first");
        second.setName("password.second");
        JLabel intro = new JLabel("<html><div style='width:430px'>" + msg.get("password.intro") + "</div></html>");
        add(intro, BorderLayout.NORTH);

        show = Mnemonics.apply(new JCheckBox(), msg.get("password.show"));
        show.setName("password.show");
        show.addActionListener(e -> showPlain(show.isSelected()));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        g.gridy = 0;
        JLabel l1 = Mnemonics.apply(new JLabel(), msg.get("password.first"));
        l1.setLabelFor(first);
        form.add(l1, g);
        g.gridx = 1;
        form.add(first, g);
        g.gridx = 2;
        JButton generate = Mnemonics.apply(new JButton(), msg.get("password.generate"));
        generate.setName("password.generate");
        generate.addActionListener(e -> {
            String p = generator.get();
            first.setText(p);
            second.setText(p);
            show.setSelected(true);
            showPlain(true);
        });
        form.add(generate, g);
        g.gridx = 0;
        g.gridy = 1;
        JLabel l2 = Mnemonics.apply(new JLabel(), msg.get("password.second"));
        l2.setLabelFor(second);
        form.add(l2, g);
        g.gridx = 1;
        form.add(second, g);
        g.gridx = 1;
        g.gridy = 2;
        form.add(show, g);
        add(form, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        JPanel print = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton sheet = Mnemonics.apply(new JButton(), msg.get("password.print"));
        sheet.setName("password.print");
        sheet.addActionListener(e -> {
            if (problem() == null) printSheet.accept(password());
        });
        print.add(sheet);
        south.add(print, BorderLayout.NORTH);
        kept = Mnemonics.apply(new JCheckBox(), msg.get("password.kept"));
        kept.setName("password.kept");
        kept.addActionListener(e -> update());
        south.add(kept, BorderLayout.CENTER);
        problem.setForeground(new java.awt.Color(0xA94442));
        problem.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        south.add(problem, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        };
        first.getDocument().addDocumentListener(listener);
        second.getDocument().addDocumentListener(listener);
        update();
    }

    private void showPlain(boolean plain) {
        first.setEchoChar(plain ? (char) 0 : echo);
        second.setEchoChar(plain ? (char) 0 : echo);
    }

    public void onChange(Runnable action) {
        changed = action;
    }

    private void update() {
        String p = problem();
        problem.setText(p == null || (first.getPassword().length == 0 && second.getPassword().length == 0) ? " " : p);
        changed.run();
    }

    /** What is still wrong, or null when the password can be used. */
    String problem() {
        String a = new String(first.getPassword());
        String b = new String(second.getPassword());
        if (a.length() < MIN_LENGTH) return msg.format("password.too-short", MIN_LENGTH);
        if (!a.equals(b)) return msg.get("password.different");
        return null;
    }

    public boolean complete() {
        return problem() == null && kept.isSelected();
    }

    public String password() {
        return new String(first.getPassword());
    }

    /** For tests and the assistant's screenshots. */
    public void setPassword(String password, boolean noted) {
        first.setText(password);
        second.setText(password);
        kept.setSelected(noted);
        update();
    }
}

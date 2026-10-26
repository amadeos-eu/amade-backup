package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.commons.i18n.Messages;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Optional;

/** Asks for the password of an existing backup, e.g. after a new installation. */
public final class PasswordDialog {
    /** The password and whether to keep it in the keyring. */
    public record Answer(String password, boolean remember) {
    }

    private PasswordDialog() {
    }

    public static Optional<Answer> ask(Component parent, Messages msg, String jobName, String problem) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        String text = msg.format("ask-password.text", jobName);
        if (problem != null) text = problem + "<br><br>" + text;
        panel.add(new JLabel("<html><div style='width:360px'>" + text + "</div></html>"), BorderLayout.NORTH);
        JPasswordField field = new JPasswordField(28);
        field.setName("ask-password.field");
        panel.add(field, BorderLayout.CENTER);
        JCheckBox remember = new JCheckBox(msg.get("ask-password.remember"), true);
        panel.add(remember, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(parent, panel, msg.get("ask-password.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION || field.getPassword().length == 0) return Optional.empty();
        return Optional.of(new Answer(new String(field.getPassword()), remember.isSelected()));
    }
}

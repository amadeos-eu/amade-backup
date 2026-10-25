package eu.amadeos.apps.backup.ui.edit;

import eu.amadeos.apps.backup.domain.KeepPolicy;
import eu.amadeos.apps.backup.domain.Schedule;
import eu.amadeos.apps.backup.ui.common.Describe;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;

/** When a job runs and, for backups, which older backups stay. */
public final class ScheduleKeepPanel extends JPanel {
    private final Messages msg;
    private final JRadioButton daily;
    private final JRadioButton weekly;
    private final JRadioButton manual;
    private final JComboBox<String> dailyTime;
    private final JComboBox<String> weeklyTime;
    private final JComboBox<String> day;
    private final JCheckBox pluggedIn;
    private final JSpinner within = spinner(0, 365);
    private final JSpinner days = spinner(0, 365);
    private final JSpinner weeks = spinner(0, 520);
    private final JSpinner months = spinner(0, 600);
    private final JSpinner years = spinner(0, 100);
    private final boolean withKeep;

    public ScheduleKeepPanel(Messages msg, Schedule schedule, KeepPolicy keep) {
        super(new BorderLayout(0, 8));
        this.msg = msg;
        this.withKeep = keep != null;
        List<String> times = new ArrayList<>();
        for (int m = 0; m < 24 * 60; m += 30) times.add(LocalTime.of(m / 60, m % 60).toString());
        String time = schedule.time().toString();
        if (!times.contains(time)) times.add(time);
        times.sort(null);
        dailyTime = new JComboBox<>(times.toArray(String[]::new));
        weeklyTime = new JComboBox<>(times.toArray(String[]::new));
        dailyTime.setSelectedItem(time);
        weeklyTime.setSelectedItem(time);
        List<String> dayNames = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) dayNames.add(d.getDisplayName(TextStyle.FULL, msg.locale()));
        day = new JComboBox<>(dayNames.toArray(String[]::new));
        day.setSelectedIndex(schedule.day().ordinal());

        daily = Mnemonics.apply(new JRadioButton(), msg.get("schedule.choice.daily"));
        daily.setName("schedule.daily");
        weekly = Mnemonics.apply(new JRadioButton(), msg.get("schedule.choice.weekly"));
        weekly.setName("schedule.weekly");
        manual = Mnemonics.apply(new JRadioButton(), msg.get("schedule.choice.manual"));
        manual.setName("schedule.manual");
        ButtonGroup group = new ButtonGroup();
        group.add(daily);
        group.add(weekly);
        group.add(manual);
        switch (schedule.frequency()) {
            case DAILY -> daily.setSelected(true);
            case WEEKLY -> weekly.setSelected(true);
            case MANUAL -> manual.setSelected(true);
        }
        pluggedIn = Mnemonics.apply(new JCheckBox(), msg.get("schedule.choice.plugged-in"));
        pluggedIn.setName("schedule.plugged-in");
        pluggedIn.setSelected(schedule.whenPluggedIn());

        JPanel when = new JPanel(new GridBagLayout());
        when.setBorder(new TitledBorder(msg.get("schedule.title")));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 4, 2, 4);
        g.gridy = 0;
        when.add(daily, g);
        when.add(flow(new JLabel(msg.get("schedule.at")), dailyTime), g);
        g.gridy = 1;
        when.add(weekly, g);
        when.add(flow(day, new JLabel(msg.get("schedule.at")), weeklyTime), g);
        g.gridy = 2;
        when.add(manual, g);
        g.gridy = 3;
        g.gridwidth = 2;
        when.add(pluggedIn, g);
        g.gridy = 4;
        JLabel missed = new JLabel(msg.get("schedule.missed"));
        missed.setForeground(eu.amadeos.commons.swing.AmadeUI.theme().textInactive());
        when.add(missed, g);
        add(when, BorderLayout.NORTH);

        if (withKeep) {
            within.setValue(keep.withinDays());
            days.setValue(keep.daily());
            weeks.setValue(keep.weekly());
            months.setValue(keep.monthly());
            years.setValue(keep.yearly());
            JPanel k = new JPanel(new GridBagLayout());
            k.setBorder(new TitledBorder(msg.get("keep.title")));
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 4, 2, 4);
            String[] keys = {"keep.within", "keep.daily", "keep.weekly", "keep.monthly", "keep.yearly"};
            JSpinner[] spinners = {within, days, weeks, months, years};
            for (int i = 0; i < keys.length; i++) {
                c.gridy = i;
                c.gridx = 0;
                spinners[i].setName(keys[i]);
                k.add(spinners[i], c);
                c.gridx = 1;
                k.add(new JLabel(msg.get(keys[i])), c);
            }
            c.gridy = keys.length;
            c.gridx = 0;
            c.gridwidth = 2;
            JLabel note = new JLabel(msg.get("keep.note"));
            note.setForeground(eu.amadeos.commons.swing.AmadeUI.theme().textInactive());
            k.add(note, c);
            add(k, BorderLayout.CENTER);
        }
    }

    private static JPanel flow(java.awt.Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (var c : parts) p.add(c);
        return p;
    }

    private static JSpinner spinner(int min, int max) {
        return new JSpinner(new SpinnerNumberModel(0, min, max, 1));
    }

    public Schedule schedule() {
        Schedule.Frequency f = daily.isSelected() ? Schedule.Frequency.DAILY
                : weekly.isSelected() ? Schedule.Frequency.WEEKLY : Schedule.Frequency.MANUAL;
        String time = (String) (f == Schedule.Frequency.WEEKLY ? weeklyTime : dailyTime).getSelectedItem();
        return new Schedule(f, LocalTime.parse(time), DayOfWeek.values()[day.getSelectedIndex()], pluggedIn.isSelected());
    }

    public KeepPolicy keep() {
        if (!withKeep) return KeepPolicy.DEFAULT;
        return new KeepPolicy((Integer) within.getValue(), (Integer) days.getValue(), (Integer) weeks.getValue(),
                (Integer) months.getValue(), (Integer) years.getValue());
    }

    public String describeSchedule() {
        return Describe.schedule(msg, schedule());
    }

    public String describeKeep() {
        return Describe.keep(msg, keep());
    }
}

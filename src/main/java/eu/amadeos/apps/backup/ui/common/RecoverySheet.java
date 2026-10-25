package eu.amadeos.apps.backup.ui.common;

import eu.amadeos.apps.backup.domain.Job;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.print.PaperSize;
import eu.amadeos.commons.print.PrintJobs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One page to keep with the backup disk: the job, where the backup is, the password in large letters, and how
 * to get the files back with or without amadeOS.
 */
public final class RecoverySheet implements Printable {
    private final Messages msg;
    private final String job;
    private final String where;
    private final String password;
    private final String date;

    public RecoverySheet(Messages msg, Formats formats, Job job, String where, String password, Instant now) {
        this.msg = msg;
        this.job = job.name();
        this.where = where;
        this.password = password;
        this.date = formats.date(now);
    }

    /** Shows the print dialog; false if the user cancelled. */
    public boolean print() throws PrinterException {
        PaperSize paper = PaperSize.forLocale(msg.locale());
        Book book = new Book();
        book.append(this, PrintJobs.pageFormat(paper, false, 20));
        return PrintJobs.printWithDialog(book, paper, msg.get("sheet.job-name"));
    }

    @Override
    public int print(Graphics graphics, PageFormat format, int page) {
        if (page > 0) return NO_SUCH_PAGE;
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        double x = format.getImageableX();
        double width = format.getImageableWidth();
        float y = (float) format.getImageableY() + 24;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(msg.get("sheet.title"), (float) x, y);
        y += 26;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        y = line(g, msg.format("sheet.job", job), x, y);
        y = line(g, msg.format("sheet.where", where), x, y);
        y = line(g, msg.format("sheet.date", date), x, y);
        y += 18;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(msg.get("sheet.password"), (float) x, y);
        y += 10;
        g.setStroke(new BasicStroke(1.2f));
        g.drawRect((int) x, (int) y, (int) width, 46);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        g.drawString(password, (float) x + 12, y + 31);
        y += 70;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (String paragraph : msg.get("sheet.instructions").split("\n")) {
            for (String l : wrap(g.getFontMetrics(), paragraph, width)) y = line(g, l, x, y);
            y += 5;
        }
        return PAGE_EXISTS;
    }

    private static float line(Graphics2D g, String text, double x, float y) {
        g.drawString(text, (float) x, y);
        return y + g.getFontMetrics().getHeight();
    }

    static List<String> wrap(FontMetrics fm, String text, double width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}

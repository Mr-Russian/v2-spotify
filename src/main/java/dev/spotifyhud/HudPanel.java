package dev.spotifyhud;

import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Draws the neon-glass player card. All coordinates are in a fixed 1672x490 "design" space
 * that is scaled to the panel size, so the layout stays identical at any scale.
 */
final class HudPanel extends JPanel {

    interface Actions {
        void togglePlay();
        void next();
        void previous();
        void setVolume(int pct);
        void seek(long ms);
    }

    static final int DW = 1672;
    static final int DH = 490;

    // ---- layout (design space) ----
    private static final RoundRectangle2D CARD = new RoundRectangle2D.Double(68, 30, 1540, 430, 300, 300);
    private static final RoundRectangle2D ART = new RoundRectangle2D.Double(150, 85, 245, 245, 50, 50);
    private static final Rectangle2D PROG = new Rectangle2D.Double(441, 268, 1029, 17);
    private static final Rectangle2D VOL = new Rectangle2D.Double(430, 388, 840, 27);
    private static final Point2D PREV = new Point2D.Double(1165, 171);
    private static final Point2D PLAY = new Point2D.Double(1325, 171);
    private static final Point2D NEXT = new Point2D.Double(1479, 171);
    private static final double PLAY_R = 77;
    private static final double KNOB_R = 30;
    private static final double TEXT_X = 441;
    private static final double TEXT_MAX_X = 1090;

    private static final Color WHITE = new Color(246, 242, 255);
    private static final Color SUB = new Color(205, 200, 230);
    private static final Color TRACK = new Color(120, 80, 160, 90);

    private final Actions actions;
    private final boolean showBadge;

    private volatile NowPlaying np;
    private volatile BufferedImage art;
    private volatile String statusTitle = "Spotify HUD";
    private volatile String statusSub = "Starting...";

    private int volumeShown = 50;
    private double dragFrac;
    private int dragMode; // 0 none, 1 volume, 2 seek, 3 window
    private long lastVolSend;
    private Point winStartLoc;
    private Point pressScreen;

    private JPopupMenu menu;
    private Runnable onMoved = () -> { };

    HudPanel(double scale, boolean showBadge, Actions actions) {
        this.actions = actions;
        this.showBadge = showBadge;
        setOpaque(false);
        setPreferredSize(new Dimension((int) Math.round(DW * scale), (int) Math.round(DH * scale)));
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { press(e); }
            @Override public void mouseDragged(MouseEvent e) { drag(e); }
            @Override public void mouseReleased(MouseEvent e) { release(e); }
            @Override public void mouseMoved(MouseEvent e) { hover(e); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    void setMenu(JPopupMenu menu) { this.menu = menu; }
    void setOnMoved(Runnable r) { this.onMoved = r; }

    void setStatus(String title, String sub) {
        statusTitle = title;
        statusSub = sub;
        np = null;
        repaint();
    }

    void setNowPlaying(NowPlaying n) {
        np = n;
        if (n != null && n.volume() >= 0 && dragMode != 1) volumeShown = n.volume();
        repaint();
    }

    NowPlaying nowPlaying() { return np; }
    void setArt(BufferedImage img) { art = img; repaint(); }

    // ================================================================ painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(getWidth() / (double) DW, getHeight() / (double) DH);

        paintCard(g);
        paintArt(g);
        paintTexts(g);
        paintTransport(g);
        paintProgress(g);
        paintVolume(g);
        g.dispose();
    }

    private static Paint borderPaint(float alpha) {
        Color[] c = {
                new Color(40, 220, 255, a(alpha)),
                new Color(70, 110, 255, a(alpha)),
                new Color(190, 60, 255, a(alpha)),
                new Color(255, 70, 200, a(alpha)),
                new Color(255, 130, 210, a(alpha)),
        };
        return new LinearGradientPaint(68f, 0f, 1608f, 0f, new float[]{0f, 0.3f, 0.6f, 0.85f, 1f}, c);
    }

    private static int a(float f) { return Math.max(0, Math.min(255, Math.round(f * 255))); }

    private void paintCard(Graphics2D g) {
        // outer glow
        for (int i = 14; i >= 1; i--) {
            g.setPaint(borderPaint(0.035f));
            g.setStroke(new BasicStroke(9f + i * 3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(CARD);
        }
        // body
        g.setPaint(new LinearGradientPaint(68f, 0f, 1608f, 0f, new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(10, 8, 48, 242), new Color(22, 8, 60, 240), new Color(44, 8, 72, 240)}));
        g.fill(CARD);
        // inner glow
        Shape oldClip = g.getClip();
        g.clip(CARD);
        for (int i = 10; i >= 1; i--) {
            g.setPaint(borderPaint(0.045f));
            g.setStroke(new BasicStroke(9f + i * 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(CARD);
        }
        g.setClip(oldClip);
        // crisp border
        g.setPaint(borderPaint(1f));
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(CARD);
        // divider between player and volume rows
        g.setPaint(new Color(255, 255, 255, 22));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(440, 348, 1560, 348);
    }

    private void paintArt(Graphics2D g) {
        Shape old = g.getClip();
        g.clip(ART);
        BufferedImage img = art;
        if (img != null) {
            double s = Math.max(ART.getWidth() / img.getWidth(), ART.getHeight() / img.getHeight());
            double w = img.getWidth() * s, h = img.getHeight() * s;
            g.drawImage(img, (int) Math.round(ART.getX() + (ART.getWidth() - w) / 2),
                    (int) Math.round(ART.getY() + (ART.getHeight() - h) / 2),
                    (int) Math.round(w), (int) Math.round(h), null);
        } else {
            g.setPaint(new GradientPaint(0, 85, new Color(90, 50, 200), 0, 330, new Color(255, 110, 130)));
            g.fill(ART);
            g.setPaint(new Color(255, 255, 255, 190));
            // simple music note
            g.fill(new Ellipse2D.Double(228, 244, 44, 34));
            g.setStroke(new BasicStroke(9f));
            g.drawLine(269, 260, 269, 160);
            g.drawLine(269, 160, 314, 172);
        }
        g.setClip(old);
        g.setPaint(new Color(150, 120, 255, 170));
        g.setStroke(new BasicStroke(3f));
        g.draw(ART);
    }

    private void paintTexts(Graphics2D g) {
        NowPlaying n = np;
        String title = n != null ? n.title() : statusTitle;
        String sub = n != null ? n.artist() : statusSub;

        Font base = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        Font tf = base.deriveFont(Font.BOLD, 84f);
        Font sf = base.deriveFont(Font.PLAIN, 50f);

        double maxTitle = TEXT_MAX_X - TEXT_X - (showBadge && n != null ? 78 : 0);
        g.setFont(tf);
        String t = fit(g.getFontMetrics(), title, maxTitle);
        g.setPaint(WHITE);
        g.drawString(t, (float) TEXT_X, 157f);
        if (showBadge && n != null) {
            double bx = TEXT_X + g.getFontMetrics().stringWidth(t) + 22 + 28;
            drawBadge(g, bx, 128);
        }
        g.setFont(sf);
        g.setPaint(SUB);
        g.drawString(fit(g.getFontMetrics(), sub, TEXT_MAX_X - TEXT_X), (float) TEXT_X, 222f);
    }

    private static void drawBadge(Graphics2D g, double cx, double cy) {
        // 8-point scalloped blue badge with a white check
        Path2D.Double p = new Path2D.Double();
        int pts = 16;
        for (int i = 0; i < pts; i++) {
            double ang = Math.PI * 2 * i / pts;
            double r = (i % 2 == 0) ? 30 : 25;
            double x = cx + Math.cos(ang) * r, y = cy + Math.sin(ang) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        g.setPaint(new Color(40, 160, 255));
        g.fill(p);
        g.fill(new Ellipse2D.Double(cx - 27, cy - 27, 54, 54));
        g.setPaint(Color.WHITE);
        g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double chk = new Path2D.Double();
        chk.moveTo(cx - 12, cy + 1);
        chk.lineTo(cx - 3, cy + 10);
        chk.lineTo(cx + 13, cy - 9);
        g.draw(chk);
    }

    private void paintTransport(Graphics2D g) {
        NowPlaying n = np;
        boolean playing = n != null && n.playing();

        // prev / next
        g.setPaint(WHITE);
        skip(g, PREV, true);
        skip(g, NEXT, false);

        // play / pause ring
        Ellipse2D ring = new Ellipse2D.Double(PLAY.getX() - PLAY_R, PLAY.getY() - PLAY_R, PLAY_R * 2, PLAY_R * 2);
        for (int i = 6; i >= 1; i--) {
            g.setPaint(new Color(190, 90, 255, 22));
            g.setStroke(new BasicStroke(5f + i * 4f));
            g.draw(ring);
        }
        g.setPaint(new Color(30, 20, 92, 235));
        g.fill(ring);
        g.setPaint(new GradientPaint((float) (PLAY.getX() - PLAY_R), (float) (PLAY.getY() - PLAY_R), new Color(150, 90, 255),
                (float) (PLAY.getX() + PLAY_R), (float) (PLAY.getY() + PLAY_R), new Color(255, 120, 225)));
        g.setStroke(new BasicStroke(5f));
        g.draw(ring);
        g.setPaint(WHITE);
        if (playing) {
            g.fill(new RoundRectangle2D.Double(PLAY.getX() - 27, PLAY.getY() - 31, 21, 62, 6, 6));
            g.fill(new RoundRectangle2D.Double(PLAY.getX() + 6, PLAY.getY() - 31, 21, 62, 6, 6));
        } else {
            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(PLAY.getX() - 20, PLAY.getY() - 34);
            tri.lineTo(PLAY.getX() + 34, PLAY.getY());
            tri.lineTo(PLAY.getX() - 20, PLAY.getY() + 34);
            tri.closePath();
            g.fill(tri);
        }
    }

    private static void skip(Graphics2D g, Point2D c, boolean left) {
        double d = left ? -1 : 1;
        double x = c.getX(), y = c.getY();
        Path2D.Double tri = new Path2D.Double();
        // arrow points in the travel direction (left for previous, right for next); the bar sits at its tip
        tri.moveTo(x - d * 24, y - 27);
        tri.lineTo(x + d * 24, y);
        tri.lineTo(x - d * 24, y + 27);
        tri.closePath();
        g.fill(tri);
        g.fill(new RoundRectangle2D.Double(left ? x - 36 : x + 27, y - 27, 9, 54, 4, 4));
    }

    private void paintProgress(Graphics2D g) {
        NowPlaying n = np;
        long pos = dragMode == 2 && n != null ? (long) (dragFrac * n.durationMs()) : (n != null ? n.estimateProgress() : 0);
        long dur = n != null ? n.durationMs() : 0;
        double frac = dur > 0 ? Math.max(0, Math.min(1, pos / (double) dur)) : 0;

        RoundRectangle2D track = new RoundRectangle2D.Double(PROG.getX(), PROG.getY(), PROG.getWidth(), PROG.getHeight(), 17, 17);
        g.setPaint(TRACK);
        g.fill(track);
        if (frac > 0) {
            double w = Math.max(17, PROG.getWidth() * frac);
            g.setPaint(new GradientPaint((float) PROG.getX(), 0, new Color(230, 70, 235),
                    (float) (PROG.getX() + w), 0, new Color(255, 190, 245)));
            g.fill(new RoundRectangle2D.Double(PROG.getX(), PROG.getY(), w, PROG.getHeight(), 17, 17));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont(Font.PLAIN, 36f));
        g.setPaint(WHITE);
        g.drawString(fmt(pos), (float) PROG.getX(), 326f);
        String d = fmt(dur);
        g.drawString(d, (float) (PROG.getMaxX() - g.getFontMetrics().stringWidth(d)), 326f);
    }

    private void paintVolume(Graphics2D g) {
        double frac = volumeShown / 100.0;
        double cy = VOL.getCenterY();
        // speaker icon
        g.setPaint(WHITE);
        Path2D.Double sp = new Path2D.Double();
        sp.moveTo(322, cy - 10);
        sp.lineTo(338, cy - 10);
        sp.lineTo(358, cy - 28);
        sp.lineTo(358, cy + 28);
        sp.lineTo(338, cy + 10);
        sp.lineTo(322, cy + 10);
        sp.closePath();
        g.fill(sp);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (volumeShown > 0) g.draw(new Arc2D.Double(342, cy - 15, 30, 30, -50, 100, Arc2D.OPEN));
        if (volumeShown > 50) g.draw(new Arc2D.Double(334, cy - 27, 54, 54, -50, 100, Arc2D.OPEN));

        g.setPaint(TRACK);
        g.fill(new RoundRectangle2D.Double(VOL.getX(), VOL.getY(), VOL.getWidth(), VOL.getHeight(), 27, 27));
        double kx = VOL.getX() + VOL.getWidth() * frac;
        double w = Math.max(27, kx - VOL.getX());
        g.setPaint(new LinearGradientPaint((float) VOL.getX(), 0f, (float) VOL.getMaxX(), 0f, new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(200, 40, 255), new Color(90, 90, 255), new Color(40, 225, 255)}));
        g.fill(new RoundRectangle2D.Double(VOL.getX(), VOL.getY(), w, VOL.getHeight(), 27, 27));
        // knob
        for (int i = 4; i >= 1; i--) {
            g.setPaint(new Color(0, 0, 0, 28));
            g.fill(new Ellipse2D.Double(kx - KNOB_R - i * 2, cy - KNOB_R - i * 2 + 3, (KNOB_R + i * 2) * 2, (KNOB_R + i * 2) * 2));
        }
        g.setPaint(Color.WHITE);
        g.fill(new Ellipse2D.Double(kx - KNOB_R, cy - KNOB_R, KNOB_R * 2, KNOB_R * 2));

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont(Font.PLAIN, 40f));
        g.setPaint(WHITE);
        g.drawString(volumeShown + "%", 1300f, 414f);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static String fit(FontMetrics fm, String s, double max) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= max) return s;
        String ell = "...";
        int n = s.length();
        while (n > 1 && fm.stringWidth(s.substring(0, n) + ell) > max) n--;
        return s.substring(0, n).stripTrailing() + ell;
    }

    // ================================================================ mouse

    private Point2D design(MouseEvent e) {
        return new Point2D.Double(e.getX() * DW / (double) getWidth(), e.getY() * DH / (double) getHeight());
    }

    private static boolean in(Point2D p, Point2D c, double r) {
        return p.distance(c) <= r;
    }

    private boolean inVol(Point2D p) {
        return p.getX() >= VOL.getX() - 40 && p.getX() <= VOL.getMaxX() + 40 && p.getY() >= 366 && p.getY() <= 436;
    }

    private boolean inProg(Point2D p) {
        return p.getX() >= PROG.getX() - 10 && p.getX() <= PROG.getMaxX() + 10 && p.getY() >= 248 && p.getY() <= 305;
    }

    private void press(MouseEvent e) {
        if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
            if (menu != null) menu.show(this, e.getX(), e.getY());
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        Point2D p = design(e);
        dragMode = 0;
        if (in(p, PLAY, PLAY_R)) { actions.togglePlay(); return; }
        if (in(p, PREV, 50)) { actions.previous(); return; }
        if (in(p, NEXT, 50)) { actions.next(); return; }
        if (inVol(p)) { dragMode = 1; setVol(p); return; }
        NowPlaying n = np;
        if (inProg(p) && n != null && n.durationMs() > 0) { dragMode = 2; setSeekFrac(p); return; }
        dragMode = 3;
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            winStartLoc = w.getLocation();
            pressScreen = e.getLocationOnScreen();
        }
    }

    private void drag(MouseEvent e) {
        Point2D p = design(e);
        switch (dragMode) {
            case 1 -> {
                setVol(p);
                long now = System.currentTimeMillis();
                if (now - lastVolSend > 250) { lastVolSend = now; actions.setVolume(volumeShown); }
            }
            case 2 -> setSeekFrac(p);
            case 3 -> {
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w != null && pressScreen != null) {
                    Point s = e.getLocationOnScreen();
                    w.setLocation(winStartLoc.x + s.x - pressScreen.x, winStartLoc.y + s.y - pressScreen.y);
                }
            }
            default -> { }
        }
    }

    private void release(MouseEvent e) {
        if (e.isPopupTrigger() && menu != null) { menu.show(this, e.getX(), e.getY()); dragMode = 0; return; }
        NowPlaying n = np;
        if (dragMode == 1) {
            actions.setVolume(volumeShown);
        } else if (dragMode == 2 && n != null) {
            long ms = (long) (dragFrac * n.durationMs());
            np = n.withProgress(ms);
            actions.seek(ms);
        } else if (dragMode == 3) {
            onMoved.run();
        }
        dragMode = 0;
        repaint();
    }

    private void hover(MouseEvent e) {
        Point2D p = design(e);
        boolean hand = in(p, PLAY, PLAY_R) || in(p, PREV, 50) || in(p, NEXT, 50) || inVol(p) || inProg(p);
        setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void setVol(Point2D p) {
        double f = (p.getX() - VOL.getX()) / VOL.getWidth();
        volumeShown = (int) Math.round(Math.max(0, Math.min(1, f)) * 100);
        repaint();
    }

    private void setSeekFrac(Point2D p) {
        dragFrac = Math.max(0, Math.min(1, (p.getX() - PROG.getX()) / PROG.getWidth()));
        repaint();
    }
}

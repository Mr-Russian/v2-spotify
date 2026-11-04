package dev.spotifyhud;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** Borderless, transparent, always-on-top window hosting the {@link HudPanel}. */
final class PopoutWindow extends JFrame {
    private final HudPanel panel;
    private final HudConfig config;
    private final Runnable saveConfig;
    private final Runnable onQuit;
    private final Timer repaintTimer;
    private TrayIcon tray;

    PopoutWindow(HudConfig config, Runnable saveConfig, HudPanel.Actions actions, Runnable onQuit) {
        super("Spotify HUD");
        this.config = config;
        this.saveConfig = saveConfig;
        this.onQuit = onQuit;

        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setAlwaysOnTop(config.alwaysOnTop);
        setFocusableWindowState(false); // never steal keyboard focus from Minecraft

        panel = new HudPanel(config.scale, config.showBadge, actions);
        panel.setOnMoved(this::rememberLocation);
        panel.setMenu(buildMenu());
        setContentPane(panel);
        getContentPane().setBackground(new Color(0, 0, 0, 0));
        pack();
        placeInitially();

        repaintTimer = new Timer(100, e -> {
            if (isVisible()) panel.repaint();
        });
        repaintTimer.start();
        installTray();
    }

    HudPanel panel() { return panel; }

    private JPopupMenu buildMenu() {
        JPopupMenu m = new JPopupMenu();
        JCheckBoxMenuItem top = new JCheckBoxMenuItem("Always on top", config.alwaysOnTop);
        top.addActionListener(e -> {
            config.alwaysOnTop = top.isSelected();
            setAlwaysOnTop(config.alwaysOnTop);
            saveConfig.run();
        });
        JMenuItem hide = new JMenuItem("Hide (restore from tray icon)");
        hide.addActionListener(e -> setVisible(false));
        JMenuItem quit = new JMenuItem("Quit Spotify HUD");
        quit.addActionListener(e -> quit());
        m.add(top);
        m.add(hide);
        m.addSeparator();
        m.add(quit);
        return m;
    }

    private void placeInitially() {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension d = getSize();
        int x = config.x, y = config.y;
        boolean valid = x != -1 && y != -1
                && screen.intersects(new Rectangle(x, y, d.width, d.height));
        if (!valid) {
            x = screen.x + screen.width - d.width - 20;
            y = screen.y + 20;
        }
        setLocation(x, y);
    }

    private void rememberLocation() {
        config.x = getX();
        config.y = getY();
        saveConfig.run();
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(30, 215, 96));
            g.fillOval(1, 1, 30, 30);
            g.dispose();
            java.awt.PopupMenu pm = new java.awt.PopupMenu();
            java.awt.MenuItem toggle = new java.awt.MenuItem("Show / hide Spotify HUD");
            toggle.addActionListener(e -> setVisible(!isVisible()));
            java.awt.MenuItem quit = new java.awt.MenuItem("Quit Spotify HUD");
            quit.addActionListener(e -> quit());
            pm.add(toggle);
            pm.add(quit);
            tray = new TrayIcon(img, "Spotify HUD", pm);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> setVisible(!isVisible()));
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException | RuntimeException e) {
            SpotifyHudMod.LOG.warn("No tray icon: {}", e.toString());
        }
    }

    void quit() {
        repaintTimer.stop();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        dispose();
        onQuit.run();
    }

    static boolean supported() {
        return !GraphicsEnvironment.isHeadless() && Toolkit.getDefaultToolkit() != null;
    }
}

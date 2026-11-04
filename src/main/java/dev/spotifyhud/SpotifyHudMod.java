package dev.spotifyhud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class SpotifyHudMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("spotifyhud");

    @Override
    public void onInitializeClient() {
        if (GraphicsEnvironment.isHeadless()) {
            LOG.warn("Headless environment - Spotify HUD popout disabled.");
            return;
        }
        Path dir = FabricLoader.getInstance().getConfigDir();
        new Controller(dir.resolve("spotifyhud.json"), dir.resolve("spotifyhud-login.json")).start();
    }

    /** Owns the window, the Spotify client and the polling thread. */
    static final class Controller implements HudPanel.Actions {
        private final Path configFile;
        private final Path tokenFile;
        private final HudConfig config;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "spotifyhud-actions"));
        private final ExecutorService artLoader = Executors.newSingleThreadExecutor(r -> daemon(r, "spotifyhud-art"));
        private final Semaphore wake = new Semaphore(0);
        private volatile boolean running = true;

        private SpotifyApi api;
        private PopoutWindow window;
        private HudPanel panel;
        private String loadedArt;

        Controller(Path configFile, Path tokenFile) {
            this.configFile = configFile;
            this.tokenFile = tokenFile;
            this.config = HudConfig.load(configFile);
        }

        private static Thread daemon(Runnable r, String name) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        }

        void start() {
            SwingUtilities.invokeLater(() -> {
                try {
                    window = new PopoutWindow(config, () -> config.save(configFile), this, this::stop);
                    panel = window.panel();
                    window.setVisible(true);
                    if (config.clientId.isBlank()) {
                        panel.setStatus("Spotify HUD", "Add your clientId to config/spotifyhud.json");
                        return;
                    }
                    api = new SpotifyApi(config.clientId.trim(), tokenFile);
                    Thread t = daemon(this::pollLoop, "spotifyhud-poll");
                    t.start();
                } catch (Throwable e) {
                    LOG.error("Spotify HUD failed to start", e);
                }
            });
        }

        void stop() {
            running = false;
            wake.release();
            worker.shutdownNow();
            artLoader.shutdownNow();
        }

        // ------------------------------------------------------------ polling

        private void pollLoop() {
            while (running) {
                try {
                    if (!api.hasLogin()) {
                        status("Connect Spotify", "Finish logging in in your browser...");
                        api.authorize();
                    }
                    NowPlaying n = api.fetch();
                    if (n == null) {
                        status("Nothing playing", "Start Spotify on any device");
                    } else {
                        publish(n);
                    }
                    pause(config.pollSeconds);
                } catch (InterruptedException e) {
                    return;
                } catch (SpotifyApi.RateLimitException e) {
                    pause(Math.max(config.pollSeconds, e.seconds));
                } catch (SpotifyApi.AuthException e) {
                    LOG.warn("Spotify login problem: {}", e.getMessage());
                    status("Login needed", "Reconnecting to Spotify...");
                    pause(5);
                } catch (Exception e) {
                    LOG.warn("Spotify poll failed: {}", e.toString());
                    status("Spotify HUD", "Connection problem - retrying");
                    pause(Math.max(config.pollSeconds, 5));
                }
            }
        }

        private void pause(int seconds) {
            try {
                wake.tryAcquire(seconds, TimeUnit.SECONDS);
                wake.drainPermits();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }

        private void status(String t, String s) {
            SwingUtilities.invokeLater(() -> panel.setStatus(t, s));
        }

        private void publish(NowPlaying n) {
            SwingUtilities.invokeLater(() -> panel.setNowPlaying(n));
            String url = n.artUrl();
            if (url != null && !url.equals(loadedArt)) {
                loadedArt = url;
                artLoader.execute(() -> {
                    try {
                        BufferedImage img = ImageIO.read(URI.create(url).toURL());
                        SwingUtilities.invokeLater(() -> panel.setArt(img));
                    } catch (Exception e) {
                        LOG.warn("Could not load album art: {}", e.toString());
                    }
                });
            } else if (url == null && loadedArt != null) {
                loadedArt = null;
                SwingUtilities.invokeLater(() -> panel.setArt(null));
            }
        }

        // ------------------------------------------------------------ actions

        private interface Call { void run() throws Exception; }

        private void run(Call c) {
            if (api == null || !api.hasLogin()) return;
            worker.execute(() -> {
                try {
                    c.run();
                } catch (Exception e) {
                    LOG.warn("Spotify command failed: {}", e.getMessage());
                }
                wake.release(); // refresh state right away
            });
        }

        @Override public void togglePlay() {
            NowPlaying n = panel.nowPlaying();
            if (n == null) return;
            boolean wasPlaying = n.playing();
            panel.setNowPlaying(n.withPlaying(!wasPlaying));
            run(() -> { if (wasPlaying) api.pause(); else api.play(); });
        }

        @Override public void next() { run(() -> api.next()); }
        @Override public void previous() { run(() -> api.previous()); }

        @Override public void setVolume(int pct) {
            NowPlaying n = panel.nowPlaying();
            if (n != null) panel.setNowPlaying(n.withVolume(pct));
            run(() -> api.setVolume(pct));
        }

        @Override public void seek(long ms) { run(() -> api.seek(ms)); }
    }
}

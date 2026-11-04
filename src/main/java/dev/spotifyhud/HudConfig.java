package dev.spotifyhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/spotifyhud.json */
final class HudConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Client ID of your own app from https://developer.spotify.com/dashboard */
    String clientId = "";
    /** Window position on screen; -1 means "top-right corner". */
    int x = -1;
    int y = -1;
    /** Size multiplier (1.0 = 1672x490 px). */
    double scale = 0.45;
    boolean alwaysOnTop = true;
    boolean showBadge = true;
    int pollSeconds = 3;

    static HudConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                HudConfig c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), HudConfig.class);
                if (c != null) {
                    if (c.clientId == null) c.clientId = "";
                    c.scale = Math.max(0.2, Math.min(2.0, c.scale));
                    c.pollSeconds = Math.max(2, Math.min(30, c.pollSeconds));
                    return c;
                }
            }
        } catch (Exception e) {
            SpotifyHudMod.LOG.warn("Could not read {}: {}", file, e.toString());
        }
        HudConfig c = new HudConfig();
        c.save(file);
        return c;
    }

    void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SpotifyHudMod.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }
}

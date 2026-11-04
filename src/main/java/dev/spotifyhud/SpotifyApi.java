package dev.spotifyhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Spotify Web API client: PKCE login (no client secret needed) + player endpoints.
 * Playback control endpoints require a Spotify Premium account.
 */
final class SpotifyApi {
    static final String REDIRECT = "http://127.0.0.1:8888/callback";
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    static final class AuthException extends Exception {
        AuthException(String m) { super(m); }
    }

    static final class RateLimitException extends Exception {
        final int seconds;
        RateLimitException(int s) { super("rate limited for " + s + "s"); seconds = s; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String clientId;
    private final Path tokenFile;

    private String access;
    private String refresh;
    private long expiresAtMs;

    SpotifyApi(String clientId, Path tokenFile) {
        this.clientId = clientId;
        this.tokenFile = tokenFile;
        loadTokens();
    }

    boolean hasLogin() {
        return refresh != null && !refresh.isBlank();
    }

    // ------------------------------------------------------------------ auth

    /** Opens the browser, waits for the redirect, stores tokens. Blocks up to 5 minutes. */
    void authorize() throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] raw = new byte[64];
        rnd.nextBytes(raw);
        String verifier = b64(raw);
        String challenge = b64(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        byte[] st = new byte[16];
        rnd.nextBytes(st);
        String state = b64(st);

        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8888), 0);
        server.createContext("/callback", ex -> {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String body;
            if (q.containsKey("code") && state.equals(q.get("state"))) {
                body = page("Spotify HUD connected", "You can close this tab and go back to Minecraft.");
                codeFuture.complete(q.get("code"));
            } else {
                body = page("Login failed", "Spotify said: " + q.getOrDefault("error", "unknown error"));
                codeFuture.completeExceptionally(new AuthException(q.getOrDefault("error", "state mismatch")));
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("response_type", "code");
            p.put("client_id", clientId);
            p.put("scope", SCOPES);
            p.put("redirect_uri", REDIRECT);
            p.put("state", state);
            p.put("code_challenge_method", "S256");
            p.put("code_challenge", challenge);
            URI url = URI.create(AUTH_URL + "?" + form(p));
            SpotifyHudMod.LOG.info("Open this URL to log in to Spotify: {}", url);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
            }
            String code = codeFuture.get(5, TimeUnit.MINUTES);

            Map<String, String> f = new LinkedHashMap<>();
            f.put("grant_type", "authorization_code");
            f.put("code", code);
            f.put("redirect_uri", REDIRECT);
            f.put("client_id", clientId);
            f.put("code_verifier", verifier);
            storeTokens(postToken(f));
        } finally {
            server.stop(0);
        }
    }

    private JsonObject postToken(Map<String, String> f) throws IOException, InterruptedException, AuthException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(f)))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new AuthException("token endpoint " + r.statusCode() + ": " + r.body());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private synchronized void storeTokens(JsonObject t) {
        access = t.get("access_token").getAsString();
        if (t.has("refresh_token") && !t.get("refresh_token").isJsonNull()) {
            refresh = t.get("refresh_token").getAsString();
        }
        long secs = t.has("expires_in") ? t.get("expires_in").getAsLong() : 3600;
        expiresAtMs = System.currentTimeMillis() + secs * 1000L;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("refresh_token", refresh);
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SpotifyHudMod.LOG.warn("Could not save Spotify login: {}", e.toString());
        }
    }

    private void loadTokens() {
        try {
            if (Files.exists(tokenFile)) {
                JsonObject o = JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject();
                if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) {
                    refresh = o.get("refresh_token").getAsString();
                }
            }
        } catch (Exception e) {
            SpotifyHudMod.LOG.warn("Ignoring unreadable token file: {}", e.toString());
        }
    }

    private synchronized String token() throws IOException, InterruptedException, AuthException {
        if (access == null || System.currentTimeMillis() > expiresAtMs - 30_000) {
            if (refresh == null) throw new AuthException("not logged in");
            Map<String, String> f = new LinkedHashMap<>();
            f.put("grant_type", "refresh_token");
            f.put("refresh_token", refresh);
            f.put("client_id", clientId);
            try {
                storeTokens(postToken(f));
            } catch (AuthException e) {
                refresh = null;
                access = null;
                try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) { }
                throw e;
            }
        }
        return access;
    }

    // ------------------------------------------------------------------ player

    /** Returns the current playback state, or null when nothing is active. */
    NowPlaying fetch() throws IOException, InterruptedException, AuthException, RateLimitException {
        HttpResponse<String> r = request("GET", "/me/player?additional_types=episode", null);
        if (r.statusCode() == 204 || r.body() == null || r.body().isBlank()) return null;
        if (r.statusCode() >= 400) throw new IOException("player " + r.statusCode());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        JsonObject item = obj(o, "item");
        if (item == null) return null;

        String title = str(item, "name", "Unknown");
        String artist = "";
        JsonElement arts = item.get("artists");
        if (arts instanceof JsonArray a && !a.isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            for (JsonElement e : a) {
                if (e instanceof JsonObject eo) j.add(str(eo, "name", ""));
            }
            artist = j.toString();
        } else if (obj(item, "show") != null) {
            artist = str(obj(item, "show"), "name", "");
        }
        String art = null;
        JsonObject album = obj(item, "album");
        JsonElement imgs = album != null ? album.get("images") : item.get("images");
        if (imgs instanceof JsonArray ia && !ia.isEmpty() && ia.get(0) instanceof JsonObject io) {
            art = str(io, "url", null);
        }
        int vol = -1;
        JsonObject dev = obj(o, "device");
        if (dev != null && dev.get("volume_percent") != null && !dev.get("volume_percent").isJsonNull()) {
            vol = dev.get("volume_percent").getAsInt();
        }
        long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
        long duration = item.has("duration_ms") && !item.get("duration_ms").isJsonNull() ? item.get("duration_ms").getAsLong() : 0;
        boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
        return new NowPlaying(title, artist, art, progress, duration, playing, vol, System.nanoTime());
    }

    void play() throws Exception { command("PUT", "/me/player/play"); }
    void pause() throws Exception { command("PUT", "/me/player/pause"); }
    void next() throws Exception { command("POST", "/me/player/next"); }
    void previous() throws Exception { command("POST", "/me/player/previous"); }
    void setVolume(int pct) throws Exception {
        command("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, pct)));
    }
    void seek(long ms) throws Exception { command("PUT", "/me/player/seek?position_ms=" + Math.max(0, ms)); }

    private void command(String method, String path) throws Exception {
        HttpResponse<String> r = request(method, path, "");
        int s = r.statusCode();
        if (s == 403) throw new IOException("Spotify refused the command (playback control needs Premium)");
        if (s == 404) throw new IOException("No active Spotify device - start playing something first");
        if (s >= 400) throw new IOException(method + " " + path + " -> " + s + " " + r.body());
    }

    private HttpResponse<String> request(String method, String path, String body)
            throws IOException, InterruptedException, AuthException, RateLimitException {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token());
            if (body != null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.GET();
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401 && attempt == 0) {
                synchronized (this) { access = null; }
                continue;
            }
            if (r.statusCode() == 429) {
                int s = r.headers().firstValue("Retry-After").map(v -> {
                    try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 5; }
                }).orElse(5);
                throw new RateLimitException(s);
            }
            return r;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e instanceof JsonObject eo ? eo : null;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() ? e.getAsString() : def;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String form(Map<String, String> m) {
        StringJoiner j = new StringJoiner("&");
        m.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String page(String h, String p) {
        return "<!doctype html><meta charset=utf-8><body style=\"font-family:sans-serif;background:#0b0620;color:#fff;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\"><div style=\"text-align:center\">"
                + "<h1>" + h + "</h1><p>" + p.replace("<", "&lt;") + "</p></div>";
    }
}

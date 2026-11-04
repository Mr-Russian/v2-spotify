# Spotify HUD (Fabric, Minecraft 26.3)

A neon-glass Spotify "now playing" popout for Minecraft: album art, title, artist, progress
bar (click/drag to seek), prev / play-pause / next, volume slider.

It runs as a separate borderless, transparent, always-on-top window next to the game
(so it keeps working even when a GUI is open), and never steals keyboard focus.

## Requirements
- Java 25 (Minecraft 26.3 requires it)
- Fabric Loader 0.19.5+ and Fabric API 0.165.0+26.3
- Windows or Linux desktop. (macOS: Minecraft's GLFW main thread conflicts with AWT, so the popout may not open.)
- Spotify Premium for the buttons / seek / volume (Spotify only allows remote control for Premium). Display works on free accounts.

## 1. Create a free Spotify app (one time, 2 minutes)
1. Go to https://developer.spotify.com/dashboard and create an app.
2. Add this exact Redirect URI: `http://127.0.0.1:8888/callback`
3. Tick **Web API**, save, and copy the **Client ID**.

## 2. Build
```
./gradlew build
```
The jar is in `build/libs/spotifyhud-1.0.0.jar`. Put it in your `mods` folder with Fabric API.

## 3. First launch
1. Start Minecraft once. A popout appears and `config/spotifyhud.json` is created.
2. Close the game, paste your Client ID into `"clientId"`, start again.
3. Your browser opens the Spotify login. Approve it. The login is remembered
   (`config/spotifyhud-login.json` stores only a refresh token - never share that file).

## Using it
- Drag anywhere on the card to move it (position is saved).
- Right-click: always-on-top, hide, quit. A green tray icon lets you bring it back.
- Config options: `scale` (size), `alwaysOnTop`, `showBadge` (blue check beside the title), `pollSeconds`.
- Something must be playing on one of your Spotify devices (phone, desktop app, web player).

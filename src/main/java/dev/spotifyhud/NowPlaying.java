package dev.spotifyhud;

/** Snapshot of the Spotify player at the moment it was fetched. */
record NowPlaying(String title, String artist, String artUrl,
                  long progressMs, long durationMs,
                  boolean playing, int volume, long fetchedNanos) {

    /** Progress extrapolated to "now" while playing, clamped to the track length. */
    long estimateProgress() {
        long p = progressMs;
        if (playing) {
            p += (System.nanoTime() - fetchedNanos) / 1_000_000L;
        }
        if (durationMs > 0) {
            p = Math.min(p, durationMs);
        }
        return Math.max(0, p);
    }

    NowPlaying withPlaying(boolean nowPlaying) {
        return new NowPlaying(title, artist, artUrl, estimateProgress(), durationMs,
                nowPlaying, volume, System.nanoTime());
    }

    NowPlaying withVolume(int v) {
        return new NowPlaying(title, artist, artUrl, progressMs, durationMs, playing, v, fetchedNanos);
    }

    NowPlaying withProgress(long ms) {
        return new NowPlaying(title, artist, artUrl, ms, durationMs, playing, volume, System.nanoTime());
    }
}

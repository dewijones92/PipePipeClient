package org.schabi.newpipe.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker;
import androidx.media3.exoplayer.source.MediaSource;

import com.dewijones92.ytdlpkt.LocalHlsBridgeSession;
import com.dewijones92.ytdlpkt.YtdlpKt;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediasource.YtdlpBridgeMediaSource;

import java.io.File;
import java.util.UUID;

/**
 * Local-bridge playback for yt-dlp adaptive streams ({@code DeliveryMethod.YTDLP}): the bundled
 * ffmpeg fetches the selected video-only + audio-only pair from the remote host and remuxes them
 * (stream copy) into a growing local HLS playlist; ExoPlayer plays ONLY the local playlist and
 * never contacts the remote host itself. Sidesteps the containers that fail as progressive media
 * (fragmented MP4 / adaptive WebM without init or index ranges) while keeping full quality.
 */
public final class YtdlpBridge {

    private static final String TAG = "YtdlpBridge";
    private static final String CACHE_SUBDIR = "ytdlp-bridge";
    /**
     * The stub playlist has no segments until ffmpeg cuts the first one, and for a preloaded
     * queue item the stub can sit unchanged for however long the user watches the current video
     * (the download only starts when the item is played). ExoPlayer's stuck detection clocks
     * "time since the playlist last changed" against coefficient x target duration, so it must
     * effectively never fire; the session supervises ffmpeg itself.
     */
    private static final double PLAYLIST_STUCK_COEFFICIENT = 1_000_000;

    private YtdlpBridge() {
    }

    /**
     * Active bridged playback, remembered per selected-video URL so the Player can (a) know the
     * current item is bridge-delivered and (b) rebuild the source at a new offset on a far seek.
     */
    public static final class ActiveBridge {
        final VideoStream video;
        @Nullable
        final AudioStream audio;
        final MediaItemTag tag;
        /** Media time where this session's real (non-gap) content begins. */
        public final long playableFromMs;

        ActiveBridge(final VideoStream video, @Nullable final AudioStream audio,
                     final MediaItemTag tag, final long playableFromMs) {
            this.video = video;
            this.audio = audio;
            this.tag = tag;
            this.playableFromMs = playableFromMs;
        }
    }

    /** Keyed by selected video content URL; tiny LRU — entries are just stream references. */
    private static final java.util.LinkedHashMap<String, ActiveBridge> REGISTRY =
            new java.util.LinkedHashMap<String, ActiveBridge>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final java.util.Map.Entry<String, ActiveBridge> eldest) {
                    return size() > 8;
                }
            };

    /** The ActiveBridge for the currently playing item, or null if it isn't bridge-delivered. */
    @Nullable
    public static ActiveBridge infoFor(@Nullable final MediaItemTag tag) {
        if (tag == null) {
            return null;
        }
        final VideoStream selected = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream).orElse(null);
        if (selected == null) {
            return null;
        }
        synchronized (REGISTRY) {
            return REGISTRY.get(selected.getContent());
        }
    }

    /**
     * Seek-triggered remux, step 1: is [targetMs] unreachable in the current session — beyond
     * the muxed edge (a local seek would stall for minutes at ~1x mux rate) or inside the
     * session's skipped gap head?
     */
    public static boolean needsJump(@NonNull final ActiveBridge bridge, final long targetMs,
                                    final long windowDurationMs) {
        return targetMs > windowDurationMs + JUMP_AHEAD_SLACK_MS
                || targetMs < bridge.playableFromMs - JUMP_BACK_SLACK_MS;
    }

    /**
     * Seek-triggered remux, step 2 (seamless): pre-warm a jump session muxing from [targetMs]
     * WITHOUT touching the player, so the current video keeps playing during the ffmpeg warm-up.
     * When the warm session has a real (non-gap) segment ready — or after a timeout — [onReadyMain]
     * runs on the main thread; the caller then does the normal reload (setRecovery +
     * reloadPlayQueueManager), which reuses this pre-warmed session and so reaches READY almost
     * immediately (momentary buffering instead of a warm-up-long spinner). A newer jump supersedes
     * an in-flight one (its ffmpeg is killed and its callback suppressed).
     */
    public static void requestSeamlessJump(@NonNull final Context context,
                                           @NonNull final ActiveBridge bridge,
                                           final long targetMs,
                                           @NonNull final Runnable onReadyMain) {
        final String url = bridge.video.getContent();
        final int startAtSec = (int) Math.max(0, targetMs / 1000);
        final int gen;
        final LocalHlsBridgeSession session;
        synchronized (WARM) {
            final WarmJump prev = WARM.remove(url);
            if (prev != null) {
                prev.session.stop(); // supersede: kill the older warm ffmpeg
            }
            gen = ++warmGeneration;
            session = createSession(context, bridge.video, bridge.audio, startAtSec);
            session.start(); // begin ffmpeg NOW (pre-warm), not lazily on createPeriod
            WARM.put(url, new WarmJump(startAtSec, session, gen));
        }
        synchronized (PENDING_JUMPS) {
            PENDING_JUMPS.put(url, startAtSec);
        }
        Log.i(TAG, "seamless jump pre-warm to " + targetMs + "ms (gen " + gen + ")");

        final android.os.Handler main =
                new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            final long deadline = System.currentTimeMillis() + WARM_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (superseded(url, gen)) {
                    return;
                }
                if (hasRealSegment(session)) {
                    Log.i(TAG, "seamless jump warm ready (gen " + gen + ")");
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (final InterruptedException e) {
                    return;
                }
            }
            if (!superseded(url, gen)) {
                main.post(onReadyMain);
            }
        }, "ytdlp-bridge-warm").start();
    }

    private static boolean superseded(final String url, final int gen) {
        synchronized (WARM) {
            final WarmJump cur = WARM.get(url);
            return cur == null || cur.generation != gen;
        }
    }

    /** A jump session is playable once ffmpeg has cut a real fMP4 segment (EXT-X-MAP appears). */
    private static boolean hasRealSegment(final LocalHlsBridgeSession session) {
        final File pl = session.getPlaylistFile();
        if (!pl.exists()) {
            return false;
        }
        try {
            final byte[] buf = new byte[(int) pl.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(pl)) {
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
                return new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8)
                        .contains("#EXT-X-MAP");
            }
        } catch (final java.io.IOException e) {
            return false;
        }
    }

    /** Seeks this close to an edge wait in place instead of remuxing. */
    private static final long JUMP_AHEAD_SLACK_MS = 5_000;
    private static final long JUMP_BACK_SLACK_MS = 2_000;
    /** Cap the pre-warm wait; past this, reload anyway and let the session's watchdog catch up. */
    private static final long WARM_TIMEOUT_MS = 20_000;

    /** videoUrl -> pending remux start offset (sec), consumed by the next source build. */
    private static final java.util.HashMap<String, Integer> PENDING_JUMPS =
            new java.util.HashMap<>();

    /** A pre-warmed jump session waiting to be adopted by the next source build for its video. */
    private static final class WarmJump {
        final int startAtSec;
        final LocalHlsBridgeSession session;
        final int generation;

        WarmJump(final int startAtSec, final LocalHlsBridgeSession session, final int generation) {
            this.startAtSec = startAtSec;
            this.session = session;
            this.generation = generation;
        }
    }

    private static final java.util.HashMap<String, WarmJump> WARM = new java.util.HashMap<>();
    private static int warmGeneration = 0;

    /** Bridged MediaSource for a yt-dlp video-only stream (+ best audio); starts lazily. */
    @NonNull
    public static MediaSource buildBridgedSource(@NonNull final Context context,
                                                 @NonNull final VideoStream video,
                                                 @Nullable final AudioStream audio,
                                                 @NonNull final MediaItemTag tag) {
        final Integer pendingJump;
        synchronized (PENDING_JUMPS) {
            pendingJump = PENDING_JUMPS.remove(video.getContent());
        }
        return buildBridgedSource(context, video, audio, tag,
                pendingJump == null ? 0 : pendingJump);
    }

    /** As above, muxing from [startAtSec] with the skipped head declared as EXT-X-GAP. */
    @NonNull
    public static MediaSource buildBridgedSource(@NonNull final Context context,
                                                 @NonNull final VideoStream video,
                                                 @Nullable final AudioStream audio,
                                                 @NonNull final MediaItemTag tag,
                                                 final int startAtSec) {
        final LocalHlsBridgeSession session;
        // Adopt a matching pre-warmed jump session if one was prepared (seamless jump): its ffmpeg
        // is already running (maybe already has segments), so this source reaches READY fast.
        synchronized (WARM) {
            final WarmJump warm = WARM.remove(video.getContent());
            if (warm != null && warm.startAtSec == startAtSec) {
                session = warm.session;
                Log.i(TAG, "adopting pre-warmed jump session from " + startAtSec + "s");
            } else {
                if (warm != null) {
                    warm.session.stop(); // stale (offset changed); don't leak its ffmpeg
                }
                session = createSession(context, video, audio, startAtSec);
                Log.i(TAG, "bridging " + video.getResolution() + " from " + startAtSec + "s");
            }
        }
        synchronized (REGISTRY) {
            REGISTRY.put(video.getContent(),
                    new ActiveBridge(video, audio, tag, session.getStartAtMs()));
        }
        return wrapSource(context, session, tag);
    }

    /** Create a fresh bridge session (stub playlist only; ffmpeg starts lazily on createPeriod). */
    private static LocalHlsBridgeSession createSession(@NonNull final Context context,
                                                       @NonNull final VideoStream video,
                                                       @Nullable final AudioStream audio,
                                                       final int startAtSec) {
        final File outputDir = new File(new File(context.getCacheDir(), CACHE_SUBDIR),
                UUID.randomUUID().toString());
        final LocalHlsBridgeSession session = YtdlpKt.newLocalHlsBridge(
                stripPppId(video.getContent()),
                audio == null ? null : stripPppId(audio.getContent()),
                outputDir, 4, startAtSec);
        session.prepareOutput();
        return session;
    }

    /** Wrap a session's local playlist in the lazy-download bridge MediaSource. */
    private static MediaSource wrapSource(@NonNull final Context context,
                                          @NonNull final LocalHlsBridgeSession session,
                                          @NonNull final MediaItemTag tag) {
        final HlsMediaSource localHlsSource = new HlsMediaSource.Factory(
                new DefaultDataSource.Factory(context))
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory((dataSourceFactory, loadErrorHandlingPolicy,
                                            playlistParserFactory, cmcdConfiguration,
                                            loadExecutorSupplier) ->
                        new DefaultHlsPlaylistTracker(dataSourceFactory, loadErrorHandlingPolicy,
                                playlistParserFactory, cmcdConfiguration,
                                PLAYLIST_STUCK_COEFFICIENT, loadExecutorSupplier))
                .createMediaSource(new MediaItem.Builder()
                        .setTag(tag)
                        .setUri(Uri.fromFile(session.getPlaylistFile()))
                        .build());
        return new YtdlpBridgeMediaSource(localHlsSource, session);
    }

    /** Delete leftover bridge dirs (e.g. after a process kill); call once at app start. */
    public static void sweepCacheDir(@NonNull final Context context) {
        final File root = new File(context.getCacheDir(), CACHE_SUBDIR);
        new Thread(() -> {
            if (deleteRecursive(root)) {
                Log.i(TAG, "swept stale bridge dirs at " + root);
            }
        }, "ytdlp-bridge-sweep").start();
    }

    /** The client-side "&pppid=" marker is not part of the signed remote URL; ffmpeg gets it raw. */
    @NonNull
    private static String stripPppId(@NonNull final String url) {
        return url.replaceAll("[&?]pppid=[^&]*", "");
    }

    private static boolean deleteRecursive(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursive(child);
            }
        }
        return file.delete();
    }
}

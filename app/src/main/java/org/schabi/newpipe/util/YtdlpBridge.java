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
     * Seek-triggered remux, step 2: record the jump target, then have the caller run the normal
     * stream-reload flow (setRecovery + reloadPlayQueueManager). When the resolver rebuilds this
     * video's bridged source it consumes the pending target and muxes from there, with the
     * skipped head declared as EXT-X-GAP so positions stay in true media time.
     */
    public static void requestJump(@NonNull final ActiveBridge bridge, final long targetMs) {
        Log.i(TAG, "seek jump requested to " + targetMs + "ms (playableFrom="
                + bridge.playableFromMs + "ms)");
        synchronized (PENDING_JUMPS) {
            PENDING_JUMPS.put(bridge.video.getContent(), (int) (targetMs / 1000));
        }
    }

    /** Seeks this close to an edge wait in place instead of remuxing. */
    private static final long JUMP_AHEAD_SLACK_MS = 5_000;
    private static final long JUMP_BACK_SLACK_MS = 2_000;

    /** videoUrl -> pending remux start offset (sec), consumed by the next source build. */
    private static final java.util.HashMap<String, Integer> PENDING_JUMPS =
            new java.util.HashMap<>();

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
        final File outputDir = new File(new File(context.getCacheDir(), CACHE_SUBDIR),
                UUID.randomUUID().toString());
        final LocalHlsBridgeSession session = YtdlpKt.newLocalHlsBridge(
                stripPppId(video.getContent()),
                audio == null ? null : stripPppId(audio.getContent()),
                outputDir, 4, startAtSec);
        // Stub playlist only — the HLS source can prepare against it (publishing a timeline)
        // without any download; ffmpeg is launched by YtdlpBridgeMediaSource on first period
        // creation, i.e. only when this item is actually about to play.
        session.prepareOutput();
        synchronized (REGISTRY) {
            REGISTRY.put(video.getContent(),
                    new ActiveBridge(video, audio, tag, session.getStartAtMs()));
        }
        Log.i(TAG, "bridging " + video.getResolution() + " from " + startAtSec
                + "s via local HLS at " + outputDir);

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

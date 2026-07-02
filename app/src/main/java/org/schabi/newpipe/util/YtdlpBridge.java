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

    /** Bridged MediaSource for a yt-dlp video-only stream (+ best audio); starts lazily. */
    @NonNull
    public static MediaSource buildBridgedSource(@NonNull final Context context,
                                                 @NonNull final VideoStream video,
                                                 @Nullable final AudioStream audio,
                                                 @NonNull final MediaItemTag tag) {
        final File outputDir = new File(new File(context.getCacheDir(), CACHE_SUBDIR),
                UUID.randomUUID().toString());
        final LocalHlsBridgeSession session = YtdlpKt.newLocalHlsBridge(
                stripPppId(video.getContent()),
                audio == null ? null : stripPppId(audio.getContent()),
                outputDir);
        // Stub playlist only — the HLS source can prepare against it (publishing a timeline)
        // without any download; ffmpeg is launched by YtdlpBridgeMediaSource on first period
        // creation, i.e. only when this item is actually about to play.
        session.prepareOutput();
        Log.i(TAG, "bridging " + video.getResolution() + " via local HLS at " + outputDir);

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

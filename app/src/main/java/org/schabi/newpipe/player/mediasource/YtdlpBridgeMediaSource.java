package org.schabi.newpipe.player.mediasource;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.Allocator;

import com.dewijones92.ytdlpkt.LocalHlsBridgeSession;

/**
 * Ties a {@link LocalHlsBridgeSession} (bundled ffmpeg remuxing a yt-dlp video+audio pair into a
 * growing local HLS playlist) to the media source that plays its output.
 *
 * <p>Prepare eagerly, download lazily: preparation only reads the local stub playlist the session
 * wrote at build time (cheap, publishes a real timeline), while the ffmpeg download is deferred
 * to the first {@link #createPeriod} — which ExoPlayer only calls for an item it is actually
 * about to play, so queue neighbours preloaded by MediaSourceManager never spawn ffmpeg. (A lazy
 * {@code MaskingMediaSource} can't be used here: outside ExoPlayer's own playlist wrapper it
 * never publishes its placeholder timeline, so period creation deadlocks.)</p>
 *
 * <p>{@link #releaseSourceInternal} kills ffmpeg (if started) and deletes the session dir.</p>
 */
public final class YtdlpBridgeMediaSource extends CompositeMediaSource<Integer> {

    private static final String TAG = "YtdlpBridge";

    private final MediaSource localHlsSource;
    private final LocalHlsBridgeSession session;

    public YtdlpBridgeMediaSource(@NonNull final MediaSource localHlsSource,
                                  @NonNull final LocalHlsBridgeSession session) {
        this.localHlsSource = localHlsSource;
        this.session = session;
    }

    @Override
    protected void prepareSourceInternal(@Nullable final TransferListener mediaTransferListener) {
        super.prepareSourceInternal(mediaTransferListener);
        prepareChildSource(0, localHlsSource);
    }

    @Override
    protected void releaseSourceInternal() {
        Log.i(TAG, "releaseSourceInternal: stopping bridge session");
        super.releaseSourceInternal();
        session.stop();
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Integer id, final MediaSource mediaSource,
                                              final Timeline timeline) {
        refreshSourceInfo(timeline);
    }

    @Override
    public MediaPeriod createPeriod(final MediaPeriodId id, final Allocator allocator,
                                    final long startPositionUs) {
        // This item is actually about to play: launch the download (idempotent while running).
        Log.i(TAG, "createPeriod: starting bridge download");
        session.start();
        return localHlsSource.createPeriod(id, allocator, startPositionUs);
    }

    @Override
    public void releasePeriod(final MediaPeriod mediaPeriod) {
        localHlsSource.releasePeriod(mediaPeriod);
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return localHlsSource.getMediaItem();
    }
}

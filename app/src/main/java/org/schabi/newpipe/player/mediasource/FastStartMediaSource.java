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

/**
 * Progressive stand-in for a cold yt-dlp bridge start: plays a directly-playable progressive
 * stream (sub-second READY) and kicks [onFirstPlay] — the bridge pre-warm — on the first
 * {@link #createPeriod}. Deferring the kick to period creation mirrors
 * {@link YtdlpBridgeMediaSource}'s lazy download: ExoPlayer only creates periods for items it is
 * actually about to play, so preloaded queue neighbours never spawn a warming ffmpeg.
 */
public final class FastStartMediaSource extends CompositeMediaSource<Integer> {

    private static final String TAG = "YtdlpFastStart";

    private final MediaSource progressiveSource;
    private final Runnable onFirstPlay;
    private boolean kicked;

    public FastStartMediaSource(@NonNull final MediaSource progressiveSource,
                                @NonNull final Runnable onFirstPlay) {
        this.progressiveSource = progressiveSource;
        this.onFirstPlay = onFirstPlay;
    }

    @Override
    protected void prepareSourceInternal(@Nullable final TransferListener mediaTransferListener) {
        super.prepareSourceInternal(mediaTransferListener);
        prepareChildSource(0, progressiveSource);
    }

    @Override
    protected void onChildSourceInfoRefreshed(final Integer id, final MediaSource mediaSource,
                                              final Timeline timeline) {
        refreshSourceInfo(timeline);
    }

    @Override
    public MediaPeriod createPeriod(final MediaPeriodId id, final Allocator allocator,
                                    final long startPositionUs) {
        if (!kicked) {
            kicked = true;
            Log.i(TAG, "first play of progressive stand-in: warming the bridge");
            onFirstPlay.run();
        }
        return progressiveSource.createPeriod(id, allocator, startPositionUs);
    }

    @Override
    public void releasePeriod(final MediaPeriod mediaPeriod) {
        progressiveSource.releasePeriod(mediaPeriod);
    }

    @NonNull
    @Override
    public MediaItem getMediaItem() {
        return progressiveSource.getMediaItem();
    }
}

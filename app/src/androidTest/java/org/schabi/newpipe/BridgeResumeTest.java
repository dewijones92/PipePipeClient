package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.YtdlpBridge;
import org.schabi.newpipe.util.YtdlpHelper;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resume-at-position on a bridged video: when the play-queue item carries a recovery position,
 * the bridge must mux FROM there (gap head to it), so the recovery seekTo lands in muxed content
 * within a normal cold-start — not far ahead of a from-zero mux edge (which buffered for minutes).
 * Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgeResumeTest {

    private static final String TAG = "BridgeResume";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";
    private static final long RESUME_MS = 600_000; // simulate "left off at 10 min"

    private final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    private final AtomicReference<VideoSize> sizeRef = new AtomicReference<>();
    private final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void resumePositionStartsBridgeThereAndPlaysFast() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        final StreamInfo info = YtdlpHelper.getFallbackStreams(VIDEO_URL);
        VideoStream video = null;
        for (final VideoStream s : info.getVideoOnlyStreams()) {
            if (s.getDeliveryMethod() == DeliveryMethod.YTDLP
                    && s.getResolution().startsWith("720")
                    && s.getCodec() != null && !s.getCodec().startsWith("av01")) {
                video = s;
            }
        }
        assertNotNull(video);
        final AudioStream audio = info.getAudioStreams().get(0);
        final MediaItemTag tag = StreamInfoTag.of(info, Collections.singletonList(video), 0);

        // Build via the resume overload (what VideoPlaybackResolver now does with the item's
        // recovery position). The bridge must start AT the resume position.
        final MediaSource source = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag, RESUME_MS);
        assertEquals("bridge did not start at the resume position",
                RESUME_MS, YtdlpBridge.infoFor(tag).playableFromMs);

        // Play from the resume position (the recovery seekTo). Must reach READY in a normal
        // cold-start window (NOT the minutes a from-zero mux would take to reach 10 min).
        final long tStart = System.currentTimeMillis();
        createPlayer(ctx, source, RESUME_MS);
        assertTrue("resume playback never READY (from-zero mux regression?)", awaitReady(45_000));
        Log.i(TAG, "PERF resume -> ready " + (System.currentTimeMillis() - tStart) + "ms");

        Thread.sleep(5000);
        final long pos = positionMs();
        Log.i(TAG, "PERF resumed position=" + pos + "ms videoSize=" + sizeRef.get());
        assertNull("error resuming bridged playback", errorRef.get());
        assertTrue("did not resume at ~10min: " + pos,
                pos >= RESUME_MS && pos <= RESUME_MS + 40_000);
        assertNotNull("video not decoded on resume", sizeRef.get());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                playerRef.get().release());
    }

    @Test
    public void nearStartResumeStartsFromZero() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final StreamInfo info = YtdlpHelper.getFallbackStreams(VIDEO_URL);
        VideoStream video = null;
        for (final VideoStream s : info.getVideoOnlyStreams()) {
            if (s.getDeliveryMethod() == DeliveryMethod.YTDLP && s.getResolution().startsWith("720")
                    && s.getCodec() != null && !s.getCodec().startsWith("av01")) {
                video = s;
            }
        }
        assertNotNull(video);
        final AudioStream audio = info.getAudioStreams().get(0);
        final MediaItemTag tag = StreamInfoTag.of(info, Collections.singletonList(video), 0);
        // A tiny resume (< RESUME_MIN_MS) should not bother with a gap head — start from 0.
        YtdlpBridge.buildBridgedSource(ctx, video, audio, tag, 3_000L);
        assertEquals("tiny resume should start from 0",
                0L, YtdlpBridge.infoFor(tag).playableFromMs);
    }

    private void createPlayer(final Context ctx, final MediaSource source, final long startMs) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            player.setVideoSurface(PlaceholderSurface.newInstance(ctx, false));
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(final PlaybackException e) {
                    Log.e(TAG, "player error", e);
                    errorRef.set(e);
                }

                @Override
                public void onVideoSizeChanged(final VideoSize s) {
                    sizeRef.set(s);
                }
            });
            player.setMediaSource(source, startMs);
            player.setPlayWhenReady(true);
            player.prepare();
            playerRef.set(player);
        });
    }

    private boolean awaitReady(final long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            final AtomicLong state = new AtomicLong();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    state.set(playerRef.get().getPlaybackState()));
            if (state.get() == Player.STATE_READY) {
                return true;
            }
            assertNull("player error while waiting", errorRef.get());
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(50);
        }
    }

    private long positionMs() {
        final AtomicLong out = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                out.set(playerRef.get().getCurrentPosition()));
        return out.get();
    }
}

package org.schabi.newpipe;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
 * Measures the local-bridge playback path (load + scrubbing) against the direct progressive
 * (itag 18, 360p) baseline, on the production MediaSource. Numbers land in logcat under
 * "BridgePerf" as PERF lines. This is a measurement harness, not a pass/fail gate — it only
 * asserts that playback works while measuring. Needs a non-datacenter network (local emulator).
 */
@RunWith(AndroidJUnit4.class)
public class BridgePerfTest {

    private static final String TAG = "BridgePerf";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";

    private final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    private final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void measureBridgeLoadAndScrub() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // ---- resolve (shared by both paths; part of every cold load) ----
        final long tResolve = System.currentTimeMillis();
        final StreamInfo info = YtdlpHelper.getFallbackStreams(VIDEO_URL);
        perf("resolve_ms", System.currentTimeMillis() - tResolve);

        VideoStream video = null;
        for (final VideoStream s : info.getVideoOnlyStreams()) {
            if (s.getDeliveryMethod() != DeliveryMethod.YTDLP
                    || !s.getResolution().startsWith("720")) {
                continue;
            }
            // Prefer h264/VP9 over AV1: this API-23 emulator has no AV1 decoder, and we want
            // the timing of a run that really decodes video (matches the app's own pick).
            if (video == null || (video.getCodec() != null && video.getCodec().startsWith("av01"))) {
                video = s;
            }
        }
        assertNotNull("no 720p YTDLP stream", video);
        final AudioStream audio = info.getAudioStreams().get(0);
        Log.i(TAG, "bridge stream: " + video.getResolution() + " " + video.getCodec());

        // ---- bridge path: cold load ----
        final MediaItemTag tag = StreamInfoTag.of(info, Collections.singletonList(video), 0);
        final MediaSource bridged = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag);
        final long tPrepare = System.currentTimeMillis();
        createPlayer(ctx, p -> p.setMediaSource(bridged));
        assertTrue("bridge never reached READY", awaitReady(120_000));
        perf("bridge_cold_load_to_ready_ms", System.currentTimeMillis() - tPrepare);

        // ---- playable-window growth: governs how far ahead you can scrub ----
        // The window duration is how much media the bridge has muxed so far.
        final long growthStart = System.currentTimeMillis();
        long lastDuration = 0;
        for (int i = 1; i <= 6; i++) {
            Thread.sleep(5000);
            final long[] snap = snapshot();
            lastDuration = snap[1];
            perf("window_at_" + (5 * i) + "s_wall: position_ms=" + snap[0]
                    + " muxed_window_ms=" + snap[1] + " buffered_ms=" + snap[2], -1);
        }
        final double muxRate = lastDuration
                / (double) (System.currentTimeMillis() - growthStart);
        perf("mux_rate_media_per_wall_x100", Math.round(muxRate * 100));

        // ---- scrub tests on the bridge ----
        // 1. Back to near the start (already muxed + likely still cached locally).
        perfSeek("scrub_back_to_5s", 5_000, 60_000);
        // 2. To just inside the muxed edge.
        final long edge = snapshot()[1];
        perfSeek("scrub_to_muxed_edge_minus_3s", Math.max(0, edge - 3_000), 60_000);
        // 3. Far beyond the muxed window (10 min in): the known sore point — playback stalls
        //    until the mux catches up. Measure as a bounded stall, then scrub back to recover.
        perfSeek("scrub_far_beyond_muxed_10min", 600_000, 20_000);
        perfSeek("recover_scrub_back_to_10s", 10_000, 60_000);

        assertNull("bridge playback error", errorRef.get());
        releasePlayer();

        // ---- baseline: direct progressive itag 18 (360p), the non-bridge alternative ----
        VideoStream progressive = null;
        for (final VideoStream s : info.getVideoStreams()) {
            if (!s.isVideoOnly()) {
                progressive = s;
            }
        }
        assertNotNull("no progressive muxed stream", progressive);
        final String url = progressive.getContent();
        final long tProg = System.currentTimeMillis();
        try {
            createPlayer(ctx, p -> p.setMediaItem(MediaItem.fromUri(Uri.parse(url))));
            assertTrue("progressive never reached READY", awaitReady(60_000));
            perf("progressive360_cold_load_to_ready_ms", System.currentTimeMillis() - tProg);
            perfSeek("progressive360_scrub_to_10min", 600_000, 60_000);
            perfSeek("progressive360_scrub_back_to_5s", 5_000, 60_000);
        } catch (final AssertionError e) {
            // The direct googlevideo fetch is fragile outside the app's HTTP stack (UA/client
            // mismatch -> junk bytes -> NoDeclaredBrand) — the same failure class the bridge
            // exists to avoid. Report instead of failing the bridge measurements.
            perf("progressive360_BASELINE_UNAVAILABLE: " + e.getMessage(), -1);
        } finally {
            releasePlayer();
        }
    }

    /**
     * Seek and measure wall time until READY again (bounded); a timeout is reported as a stall
     * measurement, not a failure — for scrubs beyond the muxed window the stall IS the result.
     */
    private void perfSeek(final String label, final long targetMs, final long maxWaitMs)
            throws Exception {
        final long t0 = System.currentTimeMillis();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                playerRef.get().seekTo(targetMs));
        final boolean ready = awaitReady(maxWaitMs);
        final long elapsed = System.currentTimeMillis() - t0;
        final long landed = snapshot()[0];
        perf(label + ": target_ms=" + targetMs + " landed_ms=" + landed
                + (ready ? " ready_after_ms=" + elapsed
                         : " STALLED_still_buffering_after_ms=" + elapsed), -1);
    }

    /** Poll (50ms) until STATE_READY or timeout; asserts no player error while waiting. */
    private boolean awaitReady(final long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            final AtomicLong state = new AtomicLong();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    state.set(playerRef.get().getPlaybackState()));
            if (state.get() == Player.STATE_READY) {
                return true;
            }
            assertNull("player error while waiting for READY", errorRef.get());
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(50);
        }
    }

    /** [currentPosition, duration (muxed window), bufferedPosition] read on the main thread. */
    private long[] snapshot() {
        final long[] out = new long[3];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer p = playerRef.get();
            out[0] = p.getCurrentPosition();
            out[1] = p.getDuration();
            out[2] = p.getBufferedPosition();
        });
        return out;
    }

    private void createPlayer(final Context ctx,
                              final java.util.function.Consumer<ExoPlayer> setSource) {
        errorRef.set(null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            player.setVideoSurface(PlaceholderSurface.newInstance(ctx, false));
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(final PlaybackException error) {
                    Log.e(TAG, "player error", error);
                    errorRef.set(error);
                }

                @Override
                public void onVideoSizeChanged(final androidx.media3.common.VideoSize size) {
                    // Proof the video track really decodes during the timed run.
                    Log.i(TAG, "videoSize=" + size.width + "x" + size.height);
                }
            });
            setSource.accept(player);
            player.setPlayWhenReady(true);
            player.prepare();
            playerRef.set(player);
        });
    }

    private void releasePlayer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer p = playerRef.get();
            if (p != null) {
                p.release();
            }
        });
    }

    private static void perf(final String label, final long value) {
        Log.i(TAG, "PERF " + label + (value >= 0 ? "=" + value : ""));
    }
}

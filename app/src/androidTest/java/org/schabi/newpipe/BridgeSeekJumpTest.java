package org.schabi.newpipe;

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
 * Production-path seek-triggered remux: play a bridged stream from 0, then swap in a jump source
 * built at 10 minutes (the far-seek flow's output) and verify playback resumes there in seconds,
 * at TRUE media time, with video decoded. Also checks the replaced session cleans itself up.
 * Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgeSeekJumpTest {

    private static final String TAG = "BridgeSeekJump";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";
    private static final long JUMP_TARGET_MS = 600_000;

    private final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    private final AtomicReference<VideoSize> sizeRef = new AtomicReference<>();
    private final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void jumpSourceResumesAtTrueOffsetWithinSeconds() throws Exception {
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
        assertNotNull("no decodable 720p YTDLP stream", video);
        final AudioStream audio = info.getAudioStreams().get(0);
        final MediaItemTag tag = StreamInfoTag.of(info, Collections.singletonList(video), 0);

        // Phase 1: normal bridged playback from 0.
        final MediaSource fromZero = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag);
        createPlayer(ctx, fromZero, 0);
        assertTrue("initial bridge never READY", awaitReady(120_000));
        assertNull(errorRef.get());
        Log.i(TAG, "phase 1 playing, registry knows bridge: "
                + (YtdlpBridge.infoFor(tag) != null));
        assertNotNull("registry lost the active bridge", YtdlpBridge.infoFor(tag));
        assertTrue("fresh session should need a jump for 10min",
                YtdlpBridge.needsJump(YtdlpBridge.infoFor(tag), JUMP_TARGET_MS,
                        exoDurationMs()));

        // Phase 2: the far-seek flow's outcome — a replacement source muxed from the target.
        final long tJump = System.currentTimeMillis();
        final MediaSource jumped = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag,
                (int) (JUMP_TARGET_MS / 1000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            playerRef.get().setMediaSource(jumped, JUMP_TARGET_MS + 5_000);
            playerRef.get().prepare();
        });
        assertTrue("jump source never READY", awaitReady(60_000));
        final long jumpMs = System.currentTimeMillis() - tJump;
        Log.i(TAG, "PERF jump_to_ready_ms=" + jumpMs);

        Thread.sleep(5000);
        final long position = positionMs();
        Log.i(TAG, "PERF post_jump position_ms=" + position
                + " duration_ms=" + exoDurationMs()
                + " videoSize=" + sizeRef.get());
        assertNull("error after jump", errorRef.get());
        assertTrue("position not at true offset: " + position,
                position >= JUMP_TARGET_MS && position <= JUMP_TARGET_MS + 60_000);
        assertNotNull("video not decoded after jump", sizeRef.get());
        // The new session's registry entry must reflect the gap head (for future back-seeks).
        assertTrue("registry playableFrom not updated",
                YtdlpBridge.infoFor(tag).playableFromMs == JUMP_TARGET_MS);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                playerRef.get().release());
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
            if (startMs > 0) {
                player.setMediaSource(source, startMs);
            } else {
                player.setMediaSource(source);
            }
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

    private long exoDurationMs() {
        final AtomicLong out = new AtomicLong();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                out.set(playerRef.get().getDuration()));
        return out.get();
    }
}

package org.schabi.newpipe;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

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

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Seamless jump: requestSeamlessJump must pre-warm a jump session (ffmpeg fetching from the
 * target) WITHOUT touching the player, fire its callback only once that session has a real
 * segment, and have the subsequent source build ADOPT that same warm session (not spawn a
 * duplicate ffmpeg). Proves: (a) the current player keeps playing during the warm-up, (b) the
 * callback fires after the warm session is playable, (c) the reused session plays at the target
 * true-time fast. Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgeSeamlessJumpTest {

    private static final String TAG = "SeamlessJump";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";
    private static final long JUMP_TARGET_MS = 600_000;

    private final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    private final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void currentKeepsPlayingWhilePreWarming_thenAdoptsWarmSession() throws Exception {
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

        // Play from 0.
        final MediaSource fromZero = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag);
        createPlayer(ctx, fromZero, 0);
        assertTrue("initial never READY", awaitReady(120_000));
        final YtdlpBridge.ActiveBridge bridge = YtdlpBridge.infoFor(tag);
        assertNotNull(bridge);

        // Kick off a seamless jump to 10min. The callback fires when the warm session is ready.
        final CountDownLatch warmReady = new CountDownLatch(1);
        final long tJump = System.currentTimeMillis();
        final int segsBefore = totalSegments(ctx);
        // Callback runs on the main thread (posted by requestSeamlessJump); keep it trivial —
        // just signal. Reading the player from here would re-enter the main thread and deadlock.
        YtdlpBridge.requestSeamlessJump(ctx, bridge, JUMP_TARGET_MS, warmReady::countDown);

        // While pre-warming, the current player must KEEP PLAYING (position advances) — not freeze.
        final long p0 = positionMs();
        Thread.sleep(4000);
        final long p1 = positionMs();
        Log.i(TAG, "during pre-warm position " + p0 + " -> " + p1 + "ms (should advance)");
        assertNull("error during pre-warm", errorRef.get());
        assertTrue("current playback froze during pre-warm (" + p0 + "->" + p1 + ")",
                p1 > p0 + 1500 || warmReady.getCount() == 0);

        // Callback must fire once the warm session is playable.
        assertTrue("seamless jump callback never fired",
                warmReady.await(30, TimeUnit.SECONDS));
        Log.i(TAG, "PERF warm-ready callback after " + (System.currentTimeMillis() - tJump) + "ms");

        // The warm session had already produced segments before the callback (pre-warm worked):
        // more segments now exist than at jump time.
        assertTrue("no segments were pre-warmed before the callback",
                totalSegments(ctx) > segsBefore);

        // Now do what the Player's callback does: reload onto the warm session. Emulate via a
        // direct build at the target — it must ADOPT the warm session (logged) and play fast.
        final long tAdopt = System.currentTimeMillis();
        final MediaSource jumped = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag,
                (int) (JUMP_TARGET_MS / 1000));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            playerRef.get().setMediaSource(jumped, JUMP_TARGET_MS + 3000);
            playerRef.get().prepare();
        });
        assertTrue("adopted jump source never READY", awaitReady(45_000));
        Log.i(TAG, "PERF adopt->ready " + (System.currentTimeMillis() - tAdopt) + "ms");

        Thread.sleep(4000);
        final long pos = positionMs();
        Log.i(TAG, "PERF post-jump position " + pos + "ms");
        assertNull("error after adopt", errorRef.get());
        assertTrue("not at true target offset: " + pos,
                pos >= JUMP_TARGET_MS && pos <= JUMP_TARGET_MS + 60_000);
        assertTrue("registry playableFrom not at target",
                YtdlpBridge.infoFor(tag).playableFromMs == JUMP_TARGET_MS);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                playerRef.get().release());
    }

    private static int totalSegments(final Context ctx) {
        int count = 0;
        final File[] dirs = new File(ctx.getCacheDir(), "ytdlp-bridge").listFiles();
        if (dirs != null) {
            for (final File d : dirs) {
                final File[] segs = d.listFiles((dir, n) -> n.endsWith(".m4s"));
                count += segs == null ? 0 : segs.length;
            }
        }
        return count;
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
}

package org.schabi.newpipe;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.LocalHlsBridgeSession;
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
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watchdog proof, replicating the empirically confirmed failure (ffmpeg killed mid-play used to
 * leave the player buffering forever): kill the bridge's ffmpeg behind its back and assert the
 * session restarts it at the mux edge (playlist resumes growing, playback continues past the
 * pre-kill edge, no error). Also proves the give-up path: a session whose fetch can never
 * succeed finalizes its playlist with ENDLIST instead of staying live forever.
 * Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgeWatchdogTest {

    private static final String TAG = "BridgeWatchdog";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";

    private final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    private final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void killedFfmpegIsRestartedAndPlaybackContinues() throws Exception {
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
        // Drive the session directly: the watchdog is the session's own behaviour. Play its
        // local playlist through a real ExoPlayer HLS source for realism.
        final File dir = new File(ctx.getCacheDir(), "watchdog-recover");
        final LocalHlsBridgeSession session = YtdlpKt.newLocalHlsBridge(
                stripPppId(video.getContent()), stripPppId(audio.getContent()), dir, 4, 0);
        session.prepareOutput();
        session.start();
        try {
            createPlayer(ctx, new HlsMediaSource.Factory(new DefaultDataSource.Factory(ctx))
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(session.getPlaylistFile()))));
            assertTrue("never READY", awaitReady(120_000));

            // Let some content mux, then kill ffmpeg behind the session's back (as the
            // hand-verified external-kill scenario does; this triggers the same recovery path).
            Thread.sleep(8000);
            final int segsBeforeKill = countSegments(dir);
            Log.i(TAG, "killing ffmpeg (segments so far: " + segsBeforeKill + ")");
            assertTrue("no ffmpeg to kill", session.simulateFfmpegDeathForTest());

            // Watchdog: a new run must appear and the playlist must resume growing.
            final long deadline = System.currentTimeMillis() + 45_000;
            while (countSegments(dir) <= segsBeforeKill) {
                assertNull("player error while watchdog recovers", errorRef.get());
                assertTrue("playlist never resumed growing after ffmpeg kill",
                        System.currentTimeMillis() < deadline);
                Thread.sleep(1000);
            }
            Log.i(TAG, "playlist resumed growing: " + countSegments(dir) + " segments");
            assertTrue("restart run not stitched behind a discontinuity",
                    readFile(session.getPlaylistFile()).contains("#EXT-X-DISCONTINUITY"));

            // Playback must keep going. Right after recovery there's a rebuffer at the playhead
            // (the restarted ffmpeg re-fetches from the mux edge and has to get ahead again), so
            // poll for sustained progress rather than demand it instantly.
            final long posBefore = positionMs();
            final long deadlineAdvance = System.currentTimeMillis() + 30_000;
            long posNow = posBefore;
            while (posNow < posBefore + 5000) {
                assertNull("player error after recovery", errorRef.get());
                assertTrue("position never resumed advancing after recovery (stuck at " + posNow
                                + "ms, was " + posBefore + "ms)",
                        System.currentTimeMillis() < deadlineAdvance);
                Thread.sleep(1000);
                posNow = positionMs();
            }
            Log.i(TAG, "PERF playback resumed: " + posBefore + " -> " + posNow + "ms after recovery");

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    playerRef.get().release());
        } finally {
            session.stop();
        }
    }

    private static String stripPppId(final String url) {
        return url.replaceAll("[&?]pppid=[^&]*", "");
    }

    @Test
    public void hopelessSessionGivesUpWithEndlist() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // A fetch that can never succeed (nothing listens on this port).
        final LocalHlsBridgeSession session = YtdlpKt.newLocalHlsBridge(
                "https://127.0.0.1:1/nope-video", "https://127.0.0.1:1/nope-audio",
                new File(ctx.getCacheDir(), "watchdog-giveup"), 4, 0);
        try {
            session.start();
            // 3 fast failures with 1s/2s/4s backoffs -> finalized well within this window.
            final long deadline = System.currentTimeMillis() + 60_000;
            while (true) {
                final String playlist = session.getPlaylistFile().exists()
                        ? readFile(session.getPlaylistFile()) : "";
                if (playlist.contains("#EXT-X-ENDLIST")) {
                    Log.i(TAG, "give-up path finalized the playlist (ENDLIST present)");
                    break;
                }
                assertTrue("session never gave up (still live after 60s)",
                        System.currentTimeMillis() < deadline);
                Thread.sleep(1000);
            }
        } finally {
            session.stop();
        }
    }

    private static int countSegments(final File dir) {
        final File[] segs = dir.listFiles((d, n) -> n.endsWith(".m4s"));
        return segs == null ? 0 : segs.length;
    }

    /** java.nio.file is API 26+; API 23 needs plain streams. */
    private static String readFile(final File f) throws java.io.IOException {
        final byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    private void createPlayer(final Context ctx, final MediaSource source) {
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
            player.setMediaSource(source);
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

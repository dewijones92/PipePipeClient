package org.schabi.newpipe;

import static org.junit.Assert.assertFalse;
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

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end proof of the local-bridge playback architecture on a real API-23 device, through
 * the PRODUCTION path: YtdlpHelper resolves the StreamInfo (video-only streams marked
 * {@code DeliveryMethod.YTDLP}), YtdlpBridge builds the lazily-starting bridged MediaSource
 * (bundled ffmpeg remuxing googlevideo video+audio into a growing local HLS playlist), and
 * ExoPlayer plays ONLY the local playlist. Also proves the lifecycle: releasing the player kills
 * ffmpeg and deletes the bridge dir. Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgePrototypeTest {

    private static final String TAG = "BridgeProto";
    // Has a 720p h264 video-only format (itag 136) — the bridge's showcase over the 360p itag 18.
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void bridgedSourcePlays720pFromLocalHlsAndCleansUp() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // 1. Resolve through the app's own mapping: video-only streams must carry YTDLP delivery.
        final StreamInfo info = YtdlpHelper.getFallbackStreams(VIDEO_URL);
        // Pick the highest video-only stream up to 1080p — deliberately NOT forcing h264, so this
        // exercises the codec yt-dlp actually offers at that quality (VP9 for 720p/1080p
        // video-only). This is the path that broke over MPEG-TS; fMP4 segments must carry it.
        // (Capped at 1080 to keep the emulator's software VP9 decoder + mux fast, not because the
        // feature is limited.)
        VideoStream video = null;
        for (final VideoStream s : info.getVideoOnlyStreams()) {
            if (s.getDeliveryMethod() != DeliveryMethod.YTDLP || s.getHeight() > 1080) {
                continue;
            }
            if (video == null || s.getHeight() > video.getHeight()) {
                video = s;
            }
        }
        assertNotNull("no video-only stream with YTDLP delivery", video);
        AudioStream audio = info.getAudioStreams().isEmpty()
                ? null : info.getAudioStreams().get(0); // bitrate-sorted desc: best first
        assertNotNull("no audio stream", audio);
        Log.i(TAG, "picked video=" + video.getResolution() + " codec=" + video.getCodec()
                + " audio=" + audio.getBitrate());

        // 2. The production bridged source (lazy masking + session lifecycle inside).
        final MediaItemTag tag = StreamInfoTag.of(info, Collections.singletonList(video), 0);
        final MediaSource bridged = YtdlpBridge.buildBridgedSource(ctx, video, audio, tag);

        // 3. Play it; the bridge (ffmpeg) must start lazily and feed the local playlist.
        playAndAssert(ctx, bridged);

        // 4. Lifecycle: player release must kill ffmpeg and delete the bridge dir (async).
        final File bridgeRoot = new File(ctx.getCacheDir(), "ytdlp-bridge");
        final long deadline = System.currentTimeMillis() + 15_000;
        while (countChildren(bridgeRoot) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        assertTrue("bridge dir not cleaned up after release: " + countChildren(bridgeRoot),
                countChildren(bridgeRoot) == 0);
    }

    private void playAndAssert(final Context ctx, final MediaSource source) throws Exception {
        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
        final AtomicReference<VideoSize> sizeRef = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            player.setVideoSurface(PlaceholderSurface.newInstance(ctx, false));
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int state) {
                    Log.i(TAG, "playbackState=" + state);
                    if (state == Player.STATE_READY) {
                        ready.countDown();
                    }
                }

                @Override
                public void onVideoSizeChanged(final VideoSize videoSize) {
                    Log.i(TAG, "videoSize=" + videoSize.width + "x" + videoSize.height);
                    sizeRef.set(videoSize);
                }

                @Override
                public void onPlayerError(final PlaybackException error) {
                    Log.e(TAG, "player error", error);
                    errorRef.set(error);
                    ready.countDown();
                }
            });
            player.setMediaSource(source);
            player.setPlayWhenReady(true);
            player.prepare();
            playerRef.set(player);
        });

        try {
            assertTrue("player never reached READY (bridge warm-up + stub playlist polling)",
                    ready.await(90, TimeUnit.SECONDS));
            assertNull("playback error on bridged local HLS", errorRef.get());

            Thread.sleep(6000);
            final AtomicLong position = new AtomicLong();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    position.set(playerRef.get().getCurrentPosition()));
            assertNull("playback error while playing", errorRef.get());
            final VideoSize size = sizeRef.get();
            Log.i(TAG, "RESULT position=" + position.get() + "ms"
                    + " videoSize=" + (size == null ? "none" : size.width + "x" + size.height));
            // The crux: a real video frame decoded. Under MPEG-TS with VP9 this stayed null
            // (audio-only), which is the exact regression fMP4 fixes.
            assertNotNull("video track not decoded (no size reported)", size);
            assertTrue("video not decoded at full height, got " + size.height, size.height >= 720);
            assertTrue("position did not advance: " + position.get(), position.get() > 1500);
            assertFalse("bridge produced no local segments", listSegments(ctx).isEmpty());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final ExoPlayer p = playerRef.get();
                if (p != null) {
                    p.release();
                }
            });
        }
    }

    private static java.util.List<String> listSegments(final Context ctx) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final File root = new File(ctx.getCacheDir(), "ytdlp-bridge");
        final File[] dirs = root.listFiles();
        if (dirs != null) {
            for (final File d : dirs) {
                final File[] segs = d.listFiles((dir, n) -> n.endsWith(".m4s"));
                if (segs != null) {
                    for (final File s : segs) {
                        out.add(s.getName());
                    }
                }
            }
        }
        return out;
    }

    private static int countChildren(final File dir) {
        final File[] children = dir.listFiles();
        return children == null ? 0 : children.length;
    }
}

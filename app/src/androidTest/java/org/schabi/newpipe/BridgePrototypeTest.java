package org.schabi.newpipe;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.MediaFormat;
import com.dewijones92.ytdlpkt.MediaInfo;
import com.dewijones92.ytdlpkt.YtdlpKt;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-bridge playback prototype (approved long-term architecture): yt-dlp resolves the format
 * URLs, the bundled ffmpeg fetches video-only + audio-only from googlevideo and remuxes them
 * (-c copy) into a GROWING local HLS event playlist, and ExoPlayer plays ONLY the local
 * file:// playlist — it never touches googlevideo. This sidesteps the real-device
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED failures (adaptive DASH/WebM streams fed to ExoPlayer
 * as progressive) while keeping quality above the itag-18 360p ceiling.
 *
 * Proven on host first (googlevideo accepts ffmpeg's GETs; ~1x-realtime throttling noted as a
 * later perf item). Needs a non-datacenter network; run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class BridgePrototypeTest {

    private static final String TAG = "BridgeProto";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=nfe9q8ZA4Ag";

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void bridgeMuxesToLocalHlsAndVideoPlayerReachesReady() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // 1. Resolve format URLs through our stack (same call the app makes).
        final MediaInfo info = YtdlpKt.resolveBlocking(VIDEO_URL);
        MediaFormat video = null;
        MediaFormat audio = null;
        for (final MediaFormat f : info.getFormats()) {
            final String url = f.getUrl();
            if (url == null || !url.startsWith("http") || f.getManifestUrl() != null) {
                continue;
            }
            final boolean vNone = f.getVcodec() == null || "none".equals(f.getVcodec());
            final boolean aNone = f.getAcodec() == null || "none".equals(f.getAcodec());
            if (!vNone && aNone && f.getVcodec().startsWith("avc")
                    && f.getHeight() > 0 && f.getHeight() <= 720
                    && (video == null || f.getHeight() > video.getHeight())) {
                video = f; // best h264 video-only up to 720p (reliable HW/SW decode on old devices)
            } else if (vNone && !aNone && f.getAcodec().startsWith("mp4a")
                    && (audio == null || f.getTotalBitrateKbps() > audio.getTotalBitrateKbps())) {
                audio = f; // best AAC audio-only (muxes cleanly into TS segments)
            }
        }
        assertTrue("no suitable video format resolved", video != null);
        assertTrue("no suitable audio format resolved", audio != null);
        Log.i(TAG, "picked video=" + video.getFormatId() + " " + video.getHeight() + "p"
                + " audio=" + audio.getFormatId() + " " + audio.getTotalBitrateKbps() + "kbps");

        // 2. Bundled ffmpeg: googlevideo -> growing local HLS, stream copy (no re-encode).
        final File hlsDir = new File(ctx.getCacheDir(), "bridge-proto");
        deleteRecursive(hlsDir);
        hlsDir.mkdirs();
        final File playlist = new File(hlsDir, "index.m3u8");
        final Process ffmpeg = startBundledFfmpeg(ctx, video.getUrl(), audio.getUrl(),
                new File(hlsDir, "seg%04d.ts").getAbsolutePath(), playlist.getAbsolutePath());
        try {
            // Wait for the playlist + a couple of segments so the player has a startup buffer.
            final long muxDeadline = System.currentTimeMillis() + 120_000;
            while (countSegments(hlsDir) < 2 || !playlist.exists()) {
                assertTrue("ffmpeg exited early, exit=" + exitCodeOrRunning(ffmpeg),
                        isRunning(ffmpeg));
                assertTrue("timed out waiting for local HLS segments",
                        System.currentTimeMillis() < muxDeadline);
                Thread.sleep(1000);
            }
            Log.i(TAG, "local HLS live: segments=" + countSegments(hlsDir));

            // 3. ExoPlayer plays ONLY the local playlist, on a real video surface.
            playLocalHlsAndAssert(ctx, Uri.fromFile(playlist));
        } finally {
            ffmpeg.destroy();
        }
    }

    private void playLocalHlsAndAssert(final Context ctx, final Uri localPlaylist)
            throws Exception {
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
            player.setMediaSource(new HlsMediaSource.Factory(
                    new DefaultDataSource.Factory(ctx))
                    .createMediaSource(MediaItem.fromUri(localPlaylist)));
            player.setPlayWhenReady(true);
            player.prepare();
            playerRef.set(player);
        });

        try {
            assertTrue("player never reached READY", ready.await(60, TimeUnit.SECONDS));
            assertNull("playback error on local HLS", errorRef.get());

            Thread.sleep(6000);
            final AtomicLong position = new AtomicLong();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    position.set(playerRef.get().getCurrentPosition()));
            assertNull("playback error while playing", errorRef.get());
            final VideoSize size = sizeRef.get();
            Log.i(TAG, "RESULT position=" + position.get() + "ms"
                    + " videoSize=" + (size == null ? "none" : size.width + "x" + size.height));
            assertTrue("video track not decoded (no size reported)",
                    size != null && size.width > 0);
            assertTrue("position did not advance: " + position.get(), position.get() > 1500);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final ExoPlayer p = playerRef.get();
                if (p != null) {
                    p.release();
                }
            });
        }
    }

    /** Exec the bundled (L1 from-source, API-23) ffmpeg with the env YoutubeDL uses. */
    private static Process startBundledFfmpeg(final Context ctx, final String videoUrl,
            final String audioUrl, final String segmentPattern, final String playlistPath)
            throws Exception {
        final File binDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        final File ffmpeg = new File(binDir, "libffmpeg.so");
        assertTrue("bundled ffmpeg missing: " + ffmpeg, ffmpeg.exists());
        final File packages = new File(new File(ctx.getNoBackupFilesDir(), "youtubedl-android"),
                "packages");

        final ProcessBuilder pb = new ProcessBuilder(ffmpeg.getAbsolutePath(),
                "-nostdin", "-loglevel", "warning",
                "-i", videoUrl, "-i", audioUrl,
                "-map", "0:v", "-map", "1:a", "-c", "copy",
                "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "event",
                "-hls_segment_filename", segmentPattern, playlistPath);
        final Map<String, String> env = new HashMap<>(pb.environment());
        env.put("LD_LIBRARY_PATH", new File(packages, "python/usr/lib").getAbsolutePath()
                + ":" + new File(packages, "ffmpeg/usr/lib").getAbsolutePath()
                + ":" + new File(packages, "aria2c/usr/lib").getAbsolutePath());
        env.put("SSL_CERT_FILE",
                new File(packages, "python/usr/etc/tls/cert.pem").getAbsolutePath());
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG, "ffmpeg: " + line);
                }
            } catch (final Exception ignored) {
                // stream closes when ffmpeg is destroyed
            }
        }, "bridge-ffmpeg-log").start();
        return p;
    }

    private static int countSegments(final File dir) {
        final File[] segs = dir.listFiles((d, n) -> n.endsWith(".ts"));
        return segs == null ? 0 : segs.length;
    }

    private static boolean isRunning(final Process p) {
        try {
            p.exitValue();
            return false;
        } catch (final IllegalThreadStateException e) {
            return true;
        }
    }

    private static String exitCodeOrRunning(final Process p) {
        try {
            return String.valueOf(p.exitValue());
        } catch (final IllegalThreadStateException e) {
            return "running";
        }
    }

    private static void deleteRecursive(final File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}

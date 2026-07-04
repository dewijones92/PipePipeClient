package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * The original goal, verified on the real download path: an AUDIO-ONLY download
 * (-f bestaudio) with SponsorBlock removal (--sponsorblock-remove sponsor). Asserts the produced
 * file (1) is audio-only (no video track) and (2) is shorter than the full video by roughly the
 * sponsor segment length — i.e. the sponsor really was cut. Uses a video with a stable sponsor
 * segment (178.7s..237.6s => ~59s) in a 2888s video. Needs a non-datacenter network.
 */
@RunWith(AndroidJUnit4.class)
public class SponsorBlockAudioDownloadTest {

    private static final String TAG = "SBAudioDownload";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=lIliQRKXf6k";
    private static final long FULL_DURATION_SEC = 2888;
    private static final long SPONSOR_LEN_SEC = 59; // 178.7 -> 237.6

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void audioOnlyDownloadHasSponsorRemoved() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "sbaudio-test");
        deleteRecursive(dir);
        dir.mkdirs();

        // Exactly what the download dialog's audio tab + "Remove sponsor segments" now does.
        final int code = YtdlpKt.downloadBlocking(
                VIDEO_URL,
                new File(dir, "%(title)s.%(ext)s").getAbsolutePath(),
                "bestaudio", "sponsor", null);
        assertEquals("yt-dlp exit code", 0, code);

        final File[] produced = dir.listFiles((d, n) -> !n.endsWith(".part")
                && !n.endsWith(".ytdl"));
        assertNotNull(produced);
        assertTrue("no output file produced", produced.length > 0);
        final File out = produced[0];
        final long sizeMb = out.length() / (1024 * 1024);
        Log.i(TAG, "produced " + out.getName() + " (" + sizeMb + " MB)");

        final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(out.getAbsolutePath());
            final String hasVideo =
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            final String durMs =
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            final long durationSec = durMs == null ? -1 : Long.parseLong(durMs) / 1000;
            Log.i(TAG, "hasVideo=" + hasVideo + " durationSec=" + durationSec
                    + " (full=" + FULL_DURATION_SEC + ", expect ~"
                    + (FULL_DURATION_SEC - SPONSOR_LEN_SEC) + ")");

            // (1) audio-only
            assertTrue("expected audio-only, but file has a video track",
                    !"yes".equalsIgnoreCase(hasVideo));
            // (2) sponsor removed: duration is full minus ~sponsor, not the full length.
            //     Allow tolerance for keyframe-aligned cutting.
            assertTrue("duration not read", durationSec > 0);
            assertTrue("sponsor not removed — duration ~= full (" + durationSec + "s)",
                    durationSec < FULL_DURATION_SEC - (SPONSOR_LEN_SEC / 2));
            assertTrue("too much removed — duration " + durationSec + "s",
                    durationSec > FULL_DURATION_SEC - SPONSOR_LEN_SEC - 30);
        } finally {
            mmr.release();
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

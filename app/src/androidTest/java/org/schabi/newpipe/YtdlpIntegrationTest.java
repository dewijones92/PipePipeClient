package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.YtdlpHelper;

/**
 * Full L5 path on a real API-23 device: PipePipe's YtdlpHelper -> ytdlp-kt SDK -> our from-source
 * yt-dlp runtime -> NewPipe StreamInfo. Proves the integration end-to-end (NOT just that binaries
 * load). The App Application (which inits NewPipe + YtdlpKt + the flag) runs in the test process;
 * YtdlpKt.init is idempotent so we call it defensively. Needs a non-datacenter network (CI's cloud
 * IP is bot-blocked); run on the local emulator.
 */
@RunWith(AndroidJUnit4.class)
public class YtdlpIntegrationTest {

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void ytdlpPathResolvesYouTubeStreamInfoOnDevice() throws Exception {
        // Directly exercise the L5 integration mapping (yt-dlp -> StreamInfo via ytdlp-kt).
        final StreamInfo info = YtdlpHelper.getFallbackStreams(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw");
        Log.i("L5Signal", "name='" + info.getName() + "'"
                + " audio=" + info.getAudioStreams().size()
                + " video=" + info.getVideoStreams().size()
                + " videoOnly=" + info.getVideoOnlyStreams().size());
        assertNotNull(info);
        assertFalse("no streams mapped from yt-dlp",
                info.getAudioStreams().isEmpty()
                        && info.getVideoStreams().isEmpty()
                        && info.getVideoOnlyStreams().isEmpty());

        // #17: enriched metadata + subtitles + stream type populated end-to-end.
        Log.i("L5Meta", "views=" + info.getViewCount()
                + " likes=" + info.getLikeCount()
                + " uploadDate=" + info.getTextualUploadDate()
                + " category=" + info.getCategory()
                + " tags=" + info.getTags().size()
                + " uploaderUrl=" + info.getUploaderUrl()
                + " descLen=" + (info.getDescription() == null
                        ? -1 : info.getDescription().getContent().length())
                + " subtitles=" + info.getSubtitles().size()
                + " streamType=" + info.getStreamType());
        assertTrue("viewCount should be populated", info.getViewCount() > 0);
        assertEquals("should not be detected as live",
                org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM, info.getStreamType());
        assertFalse("subtitles/captions should be mapped", info.getSubtitles().isEmpty());
    }

    /** SponsorBlock-on-download path: download (worst quality, fast) with --sponsorblock-remove. */
    @org.junit.Test
    public void ytdlpDownloadsWithSponsorBlockOnDevice() throws Exception {
        final android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final java.io.File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        for (final java.io.File f : dir.listFiles((d, n) -> n.startsWith("sbtest."))) {
            f.delete();
        }
        final int code = YtdlpKt.downloadBlocking(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                new java.io.File(dir, "sbtest.%(ext)s").getAbsolutePath(),
                "worst", "sponsor", null);
        final java.io.File[] produced = dir.listFiles((d, n) -> n.startsWith("sbtest."));
        android.util.Log.i("L5Download", "exit=" + code + " produced="
                + (produced == null ? 0 : produced.length));
        assertEquals("yt-dlp download exit code", 0, code);
        assertTrue("no downloaded file", produced != null && produced.length > 0);
    }
}

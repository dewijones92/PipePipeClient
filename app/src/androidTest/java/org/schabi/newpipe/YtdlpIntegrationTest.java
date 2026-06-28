package org.schabi.newpipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
    }
}

package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.util.SponsorBlockDownloader;

import java.util.List;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;

/**
 * Proves a SponsorBlock (yt-dlp) download ends up listed in the app's Downloads page: runs a real
 * audio download via {@link SponsorBlockDownloader}, then re-reads the giga finished-missions
 * store and applies the exact liveness check {@code DownloadManager.loadFinishedMissions()} uses
 * ({@code storage.existsAsFile()}) — if that passes, the DownloadActivity lists the row.
 */
@RunWith(AndroidJUnit4.class)
public class SponsorBlockDownloadsPageTest {

    private static final String TAG = "SbDownloadsPage";
    /** "Me at the zoo" — short, so the yt-dlp download stays quick. */
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";
    private static final long TIMEOUT_MS = 240_000;

    @Test
    public void sponsorBlockDownloadAppearsInDownloadsPage() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        SponsorBlockDownloader.start(ctx, URL, "Me at the zoo", "bestaudio");

        // The downloader registers the mission after yt-dlp finishes + the file is published;
        // poll the same SQLite store the Downloads page reads.
        FinishedMission mission = null;
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (mission == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            final FinishedMissionStore store = new FinishedMissionStore(ctx);
            try {
                final List<FinishedMission> missions = store.loadFinishedMissions();
                for (final FinishedMission m : missions) {
                    if (URL.equals(m.source)) {
                        mission = m;
                        break;
                    }
                }
            } finally {
                store.close();
            }
        }

        assertNotNull("no finished mission for " + URL + " within " + TIMEOUT_MS + "ms — "
                + "the download did not get registered in the Downloads page store", mission);
        Log.i(TAG, "registered mission: " + mission + " kind=" + mission.kind
                + " length=" + mission.length);

        // The page drops rows whose file it can't see — this is the gate that matters.
        assertTrue("mission file failed existsAsFile() — DownloadActivity would drop the row",
                mission.storage.existsAsFile());
        assertTrue("mission length not recorded", mission.length > 0);
        assertEquals("bestaudio download should be kind 'a'", 'a', mission.kind);
        Log.i(TAG, "Downloads-page row is live: " + mission.storage.getUri());
    }
}

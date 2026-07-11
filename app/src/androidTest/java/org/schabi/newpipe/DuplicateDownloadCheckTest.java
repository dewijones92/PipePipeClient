package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;

/**
 * Proves the duplicate-download check: a finished mission whose file still exists is reported by
 * name; a deleted file or an unknown URL is not (so no spurious warnings).
 */
@RunWith(AndroidJUnit4.class)
public class DuplicateDownloadCheckTest {

    @Test
    public void findsExistingDownloadOnlyWhileFileExists() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String url = "https://www.youtube.com/watch?v=duptest-"
                + UUID.randomUUID().toString().substring(0, 8);
        final String name = "duplicate-check-" + UUID.randomUUID().toString().substring(0, 8)
                + ".m4a";

        final File file = new File(ctx.getCacheDir(), name);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(new byte[1024]);
        }

        final FinishedMission mission = new FinishedMission();
        mission.source = url;
        mission.length = file.length();
        mission.timestamp = System.currentTimeMillis();
        mission.kind = 'a';
        mission.storage = new StoredFileHelper(ctx, null, Uri.fromFile(file), "");

        final FinishedMissionStore store = new FinishedMissionStore(ctx);
        try {
            store.addFinishedMission(mission);
        } finally {
            store.close();
        }

        assertEquals("existing download not found by source URL", name,
                FinishedMissionStore.findExistingDownloadName(ctx, url));
        assertNull("unknown URL reported as a duplicate",
                FinishedMissionStore.findExistingDownloadName(ctx, url + "-other"));

        // Once the file is gone the warning must not fire.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        assertNull("deleted file still reported as a duplicate",
                FinishedMissionStore.findExistingDownloadName(ctx, url));
    }
}

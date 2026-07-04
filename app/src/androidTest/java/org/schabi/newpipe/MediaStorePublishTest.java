package org.schabi.newpipe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.util.MediaStorePublisher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * Proves a published download is reachable by OTHER apps: publish a file, then re-discover it
 * purely through a MediaStore query (the shared index every app uses) and open the returned
 * content:// URI — the exact path a music player or file manager would take. If the query finds
 * nothing or the stream can't be opened, other apps couldn't reach it either.
 *
 * <p>On the API-23 emulator this exercises the legacy branch (public Music dir + media-scan). The
 * scoped-storage branch (API 29+) is what the phone uses and is verified there separately.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MediaStorePublishTest {

    private static final String TAG = "MediaStorePublish";
    // Legacy publish (API < 29) writes to shared external storage, needing WRITE_EXTERNAL_STORAGE;
    // the test runner grants it via `adb shell pm grant` before this runs.

    @Test
    public void publishedAudioIsDiscoverableAndReadableViaMediaStore() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Legacy (API < 29) publish writes to shared storage; grant the runtime permission the
        // way the real app would once the user approves it (grantRuntimePermission is API 28+,
        // so use the shell). No-op on API 29+ (scoped storage needs no permission).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            final android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("pm grant " + ctx.getPackageName()
                            + " " + android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            try (InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                final byte[] drain = new byte[256];
                while (is.read(drain) >= 0) {
                    // wait for the grant to complete
                }
            }
        }
        final ContentResolver resolver = ctx.getContentResolver();

        // A file in app-private storage (as yt-dlp would leave it), with known contents.
        final byte[] payload = new byte[16 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        final File src = new File(ctx.getCacheDir(), "publish-src.m4a");
        try (FileOutputStream fos = new FileOutputStream(src)) {
            fos.write(payload);
        }
        final String displayName = "dewipipe-pubtest-"
                + UUID.randomUUID().toString().substring(0, 8) + ".m4a";

        // Publish (the step SponsorBlockDownloader now runs after a download).
        final Uri published = MediaStorePublisher.publish(ctx, src, displayName, "audio/mp4");
        assertNotNull("publish returned null", published);
        Log.i(TAG, "published as " + published);

        // "Other app" discovery: find it ONLY through a MediaStore query by display name.
        final Uri discovered = findAudioByDisplayName(resolver, displayName);
        assertNotNull("published audio not found in MediaStore — other apps can't see it",
                discovered);
        Log.i(TAG, "discovered via MediaStore query: " + discovered);

        // "Other app" access: open the discovered content:// URI and read it back.
        final byte[] readBack;
        try (InputStream in = resolver.openInputStream(discovered)) {
            assertNotNull("could not open the discovered URI", in);
            readBack = readAll(in);
        }
        assertTrue("read-back was empty", readBack.length > 0);
        assertArrayEquals("bytes read via MediaStore differ from the source", payload, readBack);
        Log.i(TAG, "read " + readBack.length + " bytes back through MediaStore — accessible");
    }

    /** Query the shared Audio index for a display name; return its content URI or null. */
    private static Uri findAudioByDisplayName(final ContentResolver resolver, final String name) {
        final Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = resolver.query(collection,
                new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DISPLAY_NAME + " = ?",
                new String[]{name}, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        }
        return null;
    }

    private static byte[] readAll(final InputStream in) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}

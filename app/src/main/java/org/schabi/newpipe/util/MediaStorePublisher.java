package org.schabi.newpipe.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes a file from app-private storage into shared media storage so OTHER apps (music
 * players, file managers) can find and open it. yt-dlp needs a real filesystem path to write to,
 * so downloads land in the app-private Downloads dir first; this then copies the finished file out.
 *
 * <p>Two eras, because the app runs from API 23 to current:
 * <ul>
 *   <li>API 29+ (scoped storage): insert into the MediaStore {@code Audio}/{@code Downloads}
 *       collection with {@code RELATIVE_PATH} + {@code IS_PENDING}; no storage permission needed.</li>
 *   <li>API 23–28: copy into the public {@code Music}/{@code Download} dir (needs
 *       {@code WRITE_EXTERNAL_STORAGE}) and media-scan it so it is indexed in MediaStore.</li>
 * </ul>
 */
public final class MediaStorePublisher {

    private static final String TAG = "MediaStorePublisher";
    private static final String SUBDIR = "DewiPipe";

    private MediaStorePublisher() {
    }

    /** A successfully published file: the Uri other apps resolve + where it landed on disk. */
    public static final class Published {
        @NonNull
        public final Uri uri;
        /** Filesystem path of the published copy; null if the provider didn't reveal one. */
        @Nullable
        public final File file;

        Published(@NonNull final Uri uri, @Nullable final File file) {
            this.uri = uri;
            this.file = file;
        }
    }

    /**
     * Copy {@code src} into shared storage as {@code displayName}. Returns the published location
     * (a content/file Uri other apps can resolve, plus the on-disk path), or null on failure.
     * Blocks (does I/O + a media scan); call off the main thread.
     */
    @Nullable
    public static Published publish(@NonNull final Context context, @NonNull final File src,
                                    @NonNull final String displayName,
                                    @NonNull final String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return publishScoped(context, src, displayName, mimeType);
            }
            return publishLegacy(context, src, displayName, mimeType);
        } catch (final Exception e) {
            Log.e(TAG, "publish failed for " + displayName, e);
            return null;
        }
    }

    private static Published publishScoped(final Context context, final File src,
                                           final String displayName, final String mimeType)
            throws Exception {
        final boolean isAudio = mimeType.startsWith("audio/");
        final ContentResolver resolver = context.getContentResolver();
        // Preferred collection first, but some MIME/collection pairs are rejected (e.g. the Audio
        // collection refuses audio/webm — Android has no extension mapping for it), so fall back
        // to the generic Downloads collection, which accepts any type.
        final Uri downloadsCollection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final String downloadsPath = Environment.DIRECTORY_DOWNLOADS + File.separator + SUBDIR;
        final Uri[] collections;
        final String[] relPaths;
        if (isAudio) {
            collections = new Uri[]{
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    downloadsCollection};
            relPaths = new String[]{
                    Environment.DIRECTORY_MUSIC + File.separator + SUBDIR, downloadsPath};
        } else {
            collections = new Uri[]{downloadsCollection};
            relPaths = new String[]{downloadsPath};
        }

        Uri item = null;
        final ContentValues values = new ContentValues();
        for (int i = 0; i < collections.length && item == null; i++) {
            values.clear();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPaths[i]);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            try {
                item = resolver.insert(collections[i], values);
            } catch (final IllegalArgumentException e) {
                Log.w(TAG, collections[i] + " rejected " + mimeType + " for " + displayName
                        + ": " + e.getMessage());
            }
        }
        if (item == null) {
            Log.e(TAG, "MediaStore insert failed in every collection for " + displayName);
            return null;
        }
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) {
                throw new java.io.IOException("openOutputStream null for " + item);
            }
            copy(src, out);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        final File path = queryFilePath(resolver, item);
        Log.i(TAG, "published to MediaStore: " + item + " at " + path);
        return new Published(item, path);
    }

    /** Where MediaStore put the item on disk (it may have de-duplicated the display name). */
    @Nullable
    private static File queryFilePath(final ContentResolver resolver, final Uri item) {
        try (android.database.Cursor c = resolver.query(item,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return new File(c.getString(0));
            }
        } catch (final Exception e) {
            Log.w(TAG, "could not resolve file path of " + item, e);
        }
        return null;
    }

    private static Published publishLegacy(final Context context, final File src,
                                           final String displayName, final String mimeType)
            throws Exception {
        final boolean isAudio = mimeType.startsWith("audio/");
        final File pubDir = new File(Environment.getExternalStoragePublicDirectory(
                isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOWNLOADS), SUBDIR);
        if (!pubDir.exists() && !pubDir.mkdirs()) {
            Log.e(TAG, "could not create public dir " + pubDir);
            return null;
        }
        final File dest = new File(pubDir, displayName);
        try (OutputStream out = new java.io.FileOutputStream(dest)) {
            copy(src, out);
        }
        // Media-scan so the file is indexed in MediaStore and visible to other apps.
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Uri> scanned = new AtomicReference<>();
        MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()},
                new String[]{mimeType}, (path, uri) -> {
                    scanned.set(uri);
                    latch.countDown();
                });
        latch.await(20, TimeUnit.SECONDS);
        final Uri result = scanned.get() != null ? scanned.get() : Uri.fromFile(dest);
        Log.i(TAG, "published to public storage: " + dest + " -> " + result);
        return new Published(result, dest);
    }

    private static void copy(final File src, final OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(src)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /** Best-effort MIME type from a filename extension (covers yt-dlp's audio/video outputs). */
    @NonNull
    public static String mimeFromName(@NonNull final String name) {
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        } else if (lower.endsWith(".webm")) {
            // yt-dlp's bestaudio here is opus-in-webm; audio/webm makes players treat it as audio.
            return "audio/webm";
        } else if (lower.endsWith(".opus") || lower.endsWith(".ogg")) {
            return "audio/ogg";
        } else if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            return "video/mp4";
        } else if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        return "application/octet-stream";
    }
}

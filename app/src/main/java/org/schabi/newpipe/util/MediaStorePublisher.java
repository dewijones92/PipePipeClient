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

    /**
     * Copy {@code src} into shared storage as {@code displayName}. Returns a content/file Uri other
     * apps can resolve, or null on failure. Blocks (does I/O + a media scan); call off the main
     * thread.
     */
    @Nullable
    public static Uri publish(@NonNull final Context context, @NonNull final File src,
                              @NonNull final String displayName, @NonNull final String mimeType) {
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

    private static Uri publishScoped(final Context context, final File src,
                                     final String displayName, final String mimeType)
            throws Exception {
        final boolean isAudio = mimeType.startsWith("audio/");
        final ContentResolver resolver = context.getContentResolver();
        final Uri collection = isAudio
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final String relPath = (isAudio ? Environment.DIRECTORY_MUSIC
                : Environment.DIRECTORY_DOWNLOADS) + File.separator + SUBDIR;

        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        final Uri item = resolver.insert(collection, values);
        if (item == null) {
            Log.e(TAG, "MediaStore insert returned null");
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
        Log.i(TAG, "published to MediaStore: " + item + " (" + relPath + "/" + displayName + ")");
        return item;
    }

    private static Uri publishLegacy(final Context context, final File src,
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
        return result;
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

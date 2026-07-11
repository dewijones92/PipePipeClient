package org.schabi.newpipe.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.service.DownloadManagerService;

/**
 * Downloads a YouTube video via our yt-dlp API-23 stack with SponsorBlock segments removed
 * (yt-dlp --sponsorblock-remove + the bundled ffmpeg). Writes to the app-specific external
 * Downloads dir (no runtime permission / no SAF needed, which keeps the yt-dlp filesystem-path
 * model simple on API 23) and reports progress via a notification. This is the original
 * "SponsorBlock-on-download" goal, surfaced in DewiPipe.
 */
public final class SponsorBlockDownloader {
    private static final String TAG = "SponsorBlockDownloader";
    private static final String CHANNEL_ID = "sponsorblock_download";
    private static final int NOTIF_ID = 0x5B10C;

    private SponsorBlockDownloader() { }

    public static void start(final Context context, final String url, final String title) {
        start(context, url, title, null);
    }

    /**
     * @param formatSelector yt-dlp {@code -f} selector (e.g. "bestaudio" for an audio-only file
     *                       when the download dialog's audio tab is selected); null = yt-dlp's
     *                       default (best video+audio).
     */
    public static void start(final Context context, final String url, final String title,
                             final String formatSelector) {
        final Context app = context.getApplicationContext();
        ensureChannel(app);
        final NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        // yt-dlp writes to a unique app-private temp dir (needs a real filesystem path); the
        // finished file is then published to shared storage so other apps can open it.
        final File tempDir = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "sb-" + java.util.UUID.randomUUID());
        tempDir.mkdirs();
        final String outputTemplate = new File(tempDir, "%(title)s.%(ext)s").getAbsolutePath();
        final String name = title == null ? "video" : title;

        notify(app, nm, name, app.getString(R.string.sponsorblock_download_started), 0, true);

        new Thread(() -> {
            int code = -1;
            boolean published = false;
            try {
                code = YtdlpKt.downloadBlocking(url, outputTemplate, formatSelector, "sponsor",
                        (percent, eta, line) -> notify(app, nm, name,
                                app.getString(R.string.sponsorblock_download_started),
                                (int) percent, true));
                if (code == 0) {
                    published = publishResult(app, url, tempDir);
                }
            } catch (final Throwable t) {
                Log.e(TAG, "SponsorBlock download failed", t);
            } finally {
                deleteRecursive(tempDir);
            }
            final boolean ok = code == 0 && published;
            notify(app, nm, name, app.getString(ok
                    ? R.string.sponsorblock_download_done
                    : R.string.sponsorblock_download_failed), ok ? 100 : 0, false);
        }, "sponsorblock-download").start();
    }

    /**
     * Publish the single file yt-dlp produced in {@code tempDir} to shared storage, and list it
     * in the app's Downloads page.
     */
    private static boolean publishResult(final Context app, final String url,
                                         final File tempDir) {
        final File[] produced = tempDir.listFiles((d, n) ->
                !n.endsWith(".part") && !n.endsWith(".ytdl"));
        if (produced == null || produced.length == 0) {
            Log.e(TAG, "no output file to publish in " + tempDir);
            return false;
        }
        final File out = produced[0];
        final String mime = MediaStorePublisher.mimeFromName(out.getName());
        final MediaStorePublisher.Published published =
                MediaStorePublisher.publish(app, out, out.getName(), mime);
        if (published == null) {
            return false;
        }
        registerInDownloadsPage(app, url, published, mime);
        return true;
    }

    /**
     * Register the published file as a finished giga mission so it shows in the Downloads page.
     * Best-effort: the download itself already succeeded if this fails.
     */
    private static void registerInDownloadsPage(final Context app, final String url,
                                                final MediaStorePublisher.Published published,
                                                final String mime) {
        if (published.file == null) {
            Log.w(TAG, "no file path for " + published.uri + "; not listing in Downloads page");
            return;
        }
        try {
            final FinishedMission mission = new FinishedMission();
            mission.source = url;
            mission.length = published.file.length();
            mission.timestamp = System.currentTimeMillis();
            mission.kind = mime.startsWith("audio/") ? 'a' : 'v';
            mission.storage = new StoredFileHelper(app, null, Uri.fromFile(published.file), "");
            // Go through the service's DownloadManager (not the SQLite store directly) so the
            // in-memory finished list a running service holds stays in sync with the database.
            final ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(final ComponentName name, final IBinder binder) {
                    ((DownloadManagerService.DownloadManagerBinder) binder)
                            .getDownloadManager().addFinishedMission(mission);
                    app.unbindService(this);
                }

                @Override
                public void onServiceDisconnected(final ComponentName name) {
                }
            };
            if (!app.bindService(new Intent(app, DownloadManagerService.class), conn,
                    Context.BIND_AUTO_CREATE)) {
                Log.e(TAG, "could not bind DownloadManagerService to register the download");
            }
        } catch (final Exception e) {
            Log.e(TAG, "failed to register the download in the Downloads page", e);
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

    private static void notify(final Context ctx, final NotificationManager nm, final String title,
                               final String text, final int progress, final boolean ongoing) {
        final NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) {
            b.setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 0);
        }
        nm.notify(NOTIF_ID, b.build());
    }

    private static void ensureChannel(final Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        "SponsorBlock downloads", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }
}

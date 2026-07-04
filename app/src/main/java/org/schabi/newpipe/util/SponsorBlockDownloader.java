package org.schabi.newpipe.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.schabi.newpipe.R;

import java.io.File;

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
        final File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        final String outputTemplate = new File(dir, "%(title)s.%(ext)s").getAbsolutePath();
        final String name = title == null ? "video" : title;

        notify(app, nm, name, app.getString(R.string.sponsorblock_download_started), 0, true);

        new Thread(() -> {
            int code = -1;
            try {
                code = YtdlpKt.downloadBlocking(url, outputTemplate, formatSelector, "sponsor",
                        (percent, eta, line) -> notify(app, nm, name,
                                app.getString(R.string.sponsorblock_download_started),
                                (int) percent, true));
            } catch (final Throwable t) {
                Log.e(TAG, "SponsorBlock download failed", t);
            }
            final boolean ok = code == 0;
            notify(app, nm, name, app.getString(ok
                    ? R.string.sponsorblock_download_done
                    : R.string.sponsorblock_download_failed), ok ? 100 : 0, false);
        }, "sponsorblock-download").start();
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

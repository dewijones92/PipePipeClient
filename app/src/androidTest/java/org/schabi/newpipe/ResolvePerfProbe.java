package org.schabi.newpipe;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dewijones92.ytdlpkt.YtdlpKt;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Measurement probe for the yt-dlp resolve cost: splits interpreter/zipapp boot from network
 * extraction and A/Bs YouTube player_client selections. Numbers land in logcat as "ResolveProbe"
 * PERF lines. Not a pass/fail gate. Reaches the underlying youtubedl-android classes via
 * reflection: they are an implementation dep of ytdlp-kt (runtime-present, compile-invisible).
 */
@RunWith(AndroidJUnit4.class)
public class ResolvePerfProbe {

    private static final String TAG = "ResolveProbe";
    private static final String VIDEO_URL = InstrumentationRegistry.getArguments()
            .getString("videoUrl", "https://www.youtube.com/watch?v=7muV3mKjqmM");

    private Object ytdl;
    private Method getInfo;
    private Method execute;
    private Class<?> requestClass;

    @BeforeClass
    public static void setUp() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YtdlpKt.INSTANCE.init(ctx);
    }

    @Test
    public void measureResolveBreakdown() throws Exception {
        final Class<?> ytdlClass = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
        requestClass = Class.forName("com.yausername.youtubedl_android.YoutubeDLRequest");
        ytdl = ytdlClass.getMethod("getInstance").invoke(null);
        getInfo = ytdlClass.getMethod("getInfo", requestClass);
        for (final Method m : ytdlClass.getMethods()) {
            if (m.getName().equals("execute") && m.getParameterCount() == 3) {
                execute = m;
                break;
            }
        }

        // 1. Interpreter + zipapp boot with no network: --version (twice: cold then warm-ish).
        for (int i = 1; i <= 2; i++) {
            final long t0 = System.currentTimeMillis();
            execute.invoke(ytdl, newRequest("", "--version"), null, null);
            perf("boot_only_version_run" + i + "_ms", System.currentTimeMillis() - t0);
        }

        // 2. Full default resolve (what production does today).
        time("resolve_default", newRequest(VIDEO_URL));

        // 3. Single player_client variants.
        for (final String client : new String[] {"android", "web", "tv"}) {
            time("resolve_client_" + client, newRequest(VIDEO_URL,
                    "--extractor-args", "youtube:player_client=" + client));
        }

        // 4. Default again (network variance check).
        time("resolve_default_again", newRequest(VIDEO_URL));
    }

    private Object newRequest(final String url, final String... options) throws Exception {
        final Object request = requestClass.getConstructor(String.class).newInstance(url);
        final Method addOption1 = requestClass.getMethod("addOption", String.class);
        final Method addOption2 = requestClass.getMethod("addOption", String.class, String.class);
        for (int i = 0; i < options.length; ) {
            if (i + 1 < options.length && options[i + 1].startsWith("youtube:")) {
                addOption2.invoke(request, options[i], options[i + 1]);
                i += 2;
            } else {
                addOption1.invoke(request, options[i]);
                i += 1;
            }
        }
        return request;
    }

    private void time(final String label, final Object request) {
        final long t0 = System.currentTimeMillis();
        try {
            final Object info = getInfo.invoke(ytdl, request);
            final Object formats = info.getClass().getMethod("getFormats").invoke(info);
            final int n = formats instanceof List ? ((List<?>) formats).size() : 0;
            perf(label + "_ms(formats=" + n + ")", System.currentTimeMillis() - t0);
        } catch (final Exception e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.i(TAG, "PERF " + label + "_FAILED after "
                    + (System.currentTimeMillis() - t0) + "ms: " + cause.getMessage());
        }
    }

    private static void perf(final String label, final long value) {
        Log.i(TAG, "PERF " + label + "=" + value);
    }
}

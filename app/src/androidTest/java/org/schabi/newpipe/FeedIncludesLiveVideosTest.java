package org.schabi.newpipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.database.feed.model.FeedGroupEntity;
import org.schabi.newpipe.database.stream.StreamWithState;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.feed.FeedDatabaseManager;
import org.schabi.newpipe.local.feed.service.FeedLoadManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.List;

/**
 * Proves the subscriptions feed includes CURRENTLY-LIVE videos: subscribe to a channel that is
 * essentially always live (Lofi Girl), run the production feed loader, and assert a
 * {@link StreamType#LIVE_STREAM} item from that channel landed in the feed the UI reads.
 */
@RunWith(AndroidJUnit4.class)
public class FeedIncludesLiveVideosTest {

    private static final String TAG = "FeedLiveTest";
    /** Lofi Girl — runs 24/7 live streams, so a live item should always exist. */
    private static final String CHANNEL_URL =
            "https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow";

    /**
     * Tests run against the app's REAL database — on a personal device a leftover subscription
     * pollutes the user's actual What's New feed (this happened: Lofi Girl at the top of the
     * feed on a real phone). Always remove what the test subscribed to.
     */
    @After
    public void unsubscribe() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new SubscriptionManager(ctx)
                .deleteSubscription(ServiceList.YouTube.getServiceId(), CHANNEL_URL)
                .blockingAwait();
    }

    @Test
    public void feedContainsCurrentlyLiveStreamAfterLoad() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int serviceId = ServiceList.YouTube.getServiceId();

        // Subscribe like the UI does.
        final ChannelInfo channelInfo =
                ExtractorHelper.getChannelInfo(serviceId, CHANNEL_URL, true).blockingGet();
        final SubscriptionManager subscriptionManager = new SubscriptionManager(ctx);
        subscriptionManager.insertSubscription(SubscriptionEntity.from(channelInfo), channelInfo);
        Log.i(TAG, "subscribed to " + channelInfo.getName());

        // Run the production feed load (ignore the outdated threshold so it fetches now).
        new FeedLoadManager(ctx)
                .startLoading(FeedGroupEntity.GROUP_ALL_ID, true)
                .blockingGet();

        // Read back what the feed UI reads.
        final List<StreamWithState> streams = new FeedDatabaseManager(ctx)
                .getStreams(FeedGroupEntity.GROUP_ALL_ID, true)
                .blockingGet();
        assertFalse("feed is empty after load", streams == null || streams.isEmpty());

        boolean foundLive = false;
        for (final StreamWithState s : streams) {
            Log.i(TAG, "feed item: [" + s.getStream().getStreamType() + "] "
                    + s.getStream().getTitle());
            if (s.getStream().getStreamType() == StreamType.LIVE_STREAM) {
                foundLive = true;
            }
        }
        assertTrue("no LIVE_STREAM item in the feed — live videos are missing", foundLive);
    }
}

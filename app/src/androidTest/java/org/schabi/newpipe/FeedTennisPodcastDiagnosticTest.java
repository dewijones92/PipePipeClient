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
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.local.feed.FeedDatabaseManager;
import org.schabi.newpipe.local.feed.service.FeedLoadManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.util.ChannelTabHelper;
import org.schabi.newpipe.util.ExtractorHelper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers a PERIODIC livestream channel (The Tennis Podcast), unlike the always-live channel in
 * {@link FeedIncludesLiveVideosTest}. Their Live tab usually carries one SCHEDULED (future-dated)
 * stream plus past VODs. The future-items feed filter used to silently drop the scheduled one;
 * the Live tab is now exempt, so this test asserts every future-dated Live-tab item the raw
 * extractor returns also lands in the feed the What's New UI reads. When the channel happens to
 * have nothing scheduled, the scheduled-stream check is vacuous and only the VOD check bites.
 */
@RunWith(AndroidJUnit4.class)
public class FeedTennisPodcastDiagnosticTest {

    private static final String TAG = "FeedTennisDiag";
    private static final String CHANNEL_URL =
            "https://www.youtube.com/channel/UC9ZPFOiLoEeOBJseKICaFFQ";

    /** Tests hit the app's REAL database; never leave a subscription behind on a real device. */
    @After
    public void unsubscribe() {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new SubscriptionManager(ctx)
                .deleteSubscription(ServiceList.YouTube.getServiceId(), CHANNEL_URL)
                .blockingAwait();
    }

    @Test
    public void scheduledLivestreamsReachTheFeed() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int serviceId = ServiceList.YouTube.getServiceId();
        final OffsetDateTime now = OffsetDateTime.now();

        final ChannelInfo channelInfo =
                ExtractorHelper.getChannelInfo(serviceId, CHANNEL_URL, true).blockingGet();
        Log.i(TAG, "channel: " + channelInfo.getName()
                + " tabs=" + channelInfo.getTabs().size());

        // RAW view: what the extractor returns per tab, before any feed filtering.
        final List<StreamInfoItem> scheduledLiveTabItems = new ArrayList<>();
        for (final ListLinkHandler tab : channelInfo.getTabs()) {
            final boolean liveTab = ChannelTabHelper.isLiveTab(tab);
            final ChannelTabInfo tabInfo =
                    ExtractorHelper.getChannelTab(serviceId, tab, true).blockingGet();
            Log.i(TAG, "tab '" + tab.getContentFilters() + "' live=" + liveTab
                    + " items=" + tabInfo.getRelatedItems().size());
            for (final InfoItem item : tabInfo.getRelatedItems()) {
                if (!(item instanceof StreamInfoItem)) {
                    continue;
                }
                final StreamInfoItem s = (StreamInfoItem) item;
                final OffsetDateTime date = s.getUploadDate() == null
                        ? null : s.getUploadDate().offsetDateTime();
                final boolean future = date != null && date.isAfter(now);
                if (liveTab && future) {
                    scheduledLiveTabItems.add(s);
                }
                Log.i(TAG, "RAW [" + s.getStreamType() + "]"
                        + " date=" + date + " future=" + future + " | " + s.getName());
            }
        }
        Log.i(TAG, "scheduled (future-dated) Live-tab items: " + scheduledLiveTabItems.size());

        // Production path: subscribe + feed load + read back what the UI reads.
        final SubscriptionManager subscriptionManager = new SubscriptionManager(ctx);
        subscriptionManager.insertSubscription(SubscriptionEntity.from(channelInfo), channelInfo);
        new FeedLoadManager(ctx)
                .startLoading(FeedGroupEntity.GROUP_ALL_ID, true)
                .blockingGet();

        final List<StreamWithState> streams = new FeedDatabaseManager(ctx)
                .getStreams(FeedGroupEntity.GROUP_ALL_ID, true)
                .blockingGet();
        assertFalse("feed is empty after load", streams == null || streams.isEmpty());
        for (final StreamWithState s : streams) {
            Log.i(TAG, "FEED [" + s.getStream().getStreamType() + "]"
                    + " date=" + s.getStream().getUploadDate()
                    + " | " + s.getStream().getTitle());
        }

        boolean foundPastVod = false;
        for (final StreamWithState s : streams) {
            final OffsetDateTime date = s.getStream().getUploadDate();
            if (date != null && date.isBefore(now)) {
                foundPastVod = true;
                break;
            }
        }
        assertTrue("no past-dated item in the feed at all", foundPastVod);

        for (final StreamInfoItem scheduled : scheduledLiveTabItems) {
            boolean inFeed = false;
            for (final StreamWithState s : streams) {
                if (scheduled.getUrl().equals(s.getStream().getUrl())) {
                    inFeed = true;
                    break;
                }
            }
            assertTrue("scheduled livestream missing from feed: " + scheduled.getName()
                    + " (" + scheduled.getUrl() + ")", inFeed);
        }
    }
}

package org.schabi.newpipe.local.holder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.local.LocalItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;

import java.time.format.DateTimeFormatter;

public abstract class PlaylistItemHolder extends LocalItemHolder {
    public final ImageView itemThumbnailView;
    final TextView itemStreamCountView;
    public final TextView itemTitleView;
    public final TextView itemUploaderView;

    public PlaylistItemHolder(final LocalItemBuilder infoItemBuilder, final int layoutId,
                              final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemTitleView = itemView.findViewById(R.id.itemTitleView);
        itemStreamCountView = itemView.findViewById(R.id.itemStreamCountView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
    }

    public PlaylistItemHolder(final LocalItemBuilder infoItemBuilder, final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_playlist_mini_item, parent);
    }

    @Override
    public void updateFromItem(final LocalItem localItem,
                               final HistoryRecordManager historyRecordManager,
                               final DateTimeFormatter dateTimeFormatter) {
        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnItemSelectedListener() != null) {
                itemBuilder.getOnItemSelectedListener().selected(localItem);
            }
        });

        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnItemSelectedListener() != null) {
                itemBuilder.getOnItemSelectedListener().held(localItem);
            }
            return true;
        });

        if (itemUploaderView != null) {
            if (android.text.TextUtils.isEmpty(itemUploaderView.getText())) {
                itemUploaderView.setVisibility(View.GONE);
            } else if (itemUploaderView.getVisibility() == View.GONE) {
                itemUploaderView.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Vertically align a drag-handle view with the uploader line, or with the title when the
     * uploader line is empty or hidden. Shared by the bookmark playlist holders.
     */
    protected void alignHandleView(final View itemHandleView) {
        if (itemHandleView == null || !(itemHandleView.getLayoutParams()
                instanceof android.widget.RelativeLayout.LayoutParams)) {
            return;
        }
        final android.widget.RelativeLayout.LayoutParams params =
                (android.widget.RelativeLayout.LayoutParams) itemHandleView.getLayoutParams();
        final int anchor = itemUploaderView == null
                || android.text.TextUtils.isEmpty(itemUploaderView.getText())
                || itemUploaderView.getVisibility() != View.VISIBLE
                ? R.id.itemTitleView : R.id.itemUploaderView;
        params.removeRule(android.widget.RelativeLayout.ALIGN_TOP);
        params.removeRule(android.widget.RelativeLayout.ALIGN_BOTTOM);
        params.addRule(android.widget.RelativeLayout.ALIGN_TOP, anchor);
        params.addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, anchor);
        itemHandleView.setLayoutParams(params);
    }
}

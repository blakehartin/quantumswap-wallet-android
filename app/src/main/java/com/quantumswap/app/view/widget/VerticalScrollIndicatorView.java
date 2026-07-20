package com.quantumswap.app.view.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Thin vertical "scrollbar" surface that mirrors a {@link RecyclerView}'s scroll
 * state. The table layouts wrap the {@code RecyclerView} inside a
 * {@link android.widget.HorizontalScrollView}, which means the native right-edge
 * scrollbar scrolls away when the user pans horizontally. To keep the user
 * oriented we place one of these indicators on either side of the scrolling
 * area (outside the {@code HorizontalScrollView}) and let the indicators track
 * vertical scroll offset, range and extent from the recycler.
 *
 * The view is intentionally lightweight: no touch handling, just a drawn track
 * and thumb. If the content fits in the viewport (range &lt;= extent) nothing
 * is drawn so the indicator quietly disappears.
 */
public class VerticalScrollIndicatorView extends View {

    private static final int TRACK_COLOR = 0x14000000; // ~8% black
    private static final int THUMB_COLOR = 0x66888888; // semi-transparent grey
    private static final float MIN_THUMB_DP = 24f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int offset = 0;
    private int range = 0;
    private int extent = 0;

    private RecyclerView attached;
    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            refreshFromRecycler(recyclerView);
        }
    };

    public VerticalScrollIndicatorView(Context context) {
        super(context);
        init();
    }

    public VerticalScrollIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VerticalScrollIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(TRACK_COLOR);
        thumbPaint.setColor(THUMB_COLOR);
    }

    /**
     * Binds this indicator to the given recycler view. Subsequent vertical
     * scrolls on the recycler will update the thumb position. Safe to call
     * multiple times; it detaches from any previously-bound recycler.
     */
    public void attachTo(final RecyclerView recyclerView) {
        if (attached == recyclerView) {
            return;
        }
        if (attached != null) {
            attached.removeOnScrollListener(scrollListener);
        }
        attached = recyclerView;
        if (recyclerView == null) {
            offset = 0;
            range = 0;
            extent = 0;
            invalidate();
            return;
        }
        recyclerView.addOnScrollListener(scrollListener);
        // Update once the recycler has a layout so range/extent are meaningful.
        recyclerView.post(new Runnable() {
            @Override
            public void run() {
                refreshFromRecycler(recyclerView);
            }
        });
    }

    private void refreshFromRecycler(RecyclerView recyclerView) {
        offset = recyclerView.computeVerticalScrollOffset();
        range = recyclerView.computeVerticalScrollRange();
        extent = recyclerView.computeVerticalScrollExtent();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // Skip drawing entirely when the content fits in the viewport,
        // so the indicator quietly disappears as the Javadoc promises.
        // Previously the track was drawn before this early-return,
        // leaving a thin always-visible stripe on either side of a
        // short table (e.g. the tokens table on accounts with only a
        // handful of tokens) which the user reads as a "scrollbar
        // that isn't doing anything".
        if (range <= 0 || extent <= 0 || range <= extent) {
            return;
        }
        canvas.drawRect(0f, 0f, w, h, trackPaint);
        float density = getResources().getDisplayMetrics().density;
        float minThumb = MIN_THUMB_DP * density;
        float thumbHeight = Math.max(h * (float) extent / (float) range, minThumb);
        thumbHeight = Math.min(thumbHeight, h);
        float denom = Math.max(range - extent, 1);
        float ratio = Math.min(1f, Math.max(0f, offset / denom));
        float thumbTop = (h - thumbHeight) * ratio;
        canvas.drawRect(0f, thumbTop, w, thumbTop + thumbHeight, thumbPaint);
    }
}

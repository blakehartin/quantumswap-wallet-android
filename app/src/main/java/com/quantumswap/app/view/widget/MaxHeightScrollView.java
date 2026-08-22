package com.quantumswap.app.view.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * ScrollView that wraps its content up to a runtime cap (desktop
 * {@code .blocks-content { max-height: ...; overflow: auto }}): short
 * lists take only the space they need, long lists scroll inside the
 * box so whatever sits below the box stays visible without scrolling
 * the page.
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) { super(context); }
    public MaxHeightScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setMaxHeightPx(int px) {
        if (px == maxHeightPx) return;
        maxHeightPx = Math.max(px, 0);
        requestLayout();
    }

    /** Cap so the box ends {@code reserveBelowPx} above the bottom of
     *  the screen, based on where the box currently sits. */
    public void capToScreen(final int reserveBelowPx) {
        post(() -> {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int avail = screenH - loc[1] - reserveBelowPx;
            int min = Math.round(120 * getResources().getDisplayMetrics().density);
            setMaxHeightPx(Math.max(avail, min));
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int cap = mode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(size, maxHeightPx);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
}

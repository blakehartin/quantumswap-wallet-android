package com.quantumswap.app.gas;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import com.quantumswap.app.R;

/**
 * Desktop gas-pulse.svg animation (1.1 s loop: opacity 0.45 -> 1 ->
 * 0.45, scale 1 -> 1.06 -> 1) applied to the rasterized pump while a
 * gas estimate is in flight.
 */
public final class GasIconPulse {

    private static final long DURATION_MS = 1100;

    private GasIconPulse() { }

    public static void start(ImageView icon) {
        if (icon == null) return;
        stop(icon);
        icon.setImageResource(R.drawable.ic_gas_pulse);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(icon, "alpha", 0.45f, 1f, 0.45f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.06f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.06f, 1f);
        for (ObjectAnimator a : new ObjectAnimator[] { alpha, sx, sy }) {
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setDuration(DURATION_MS);
            a.setInterpolator(new LinearInterpolator());
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, sx, sy);
        icon.setTag(R.id.imageView_tx_steps_gas_icon, set);
        set.start();
    }

    public static void stop(ImageView icon) {
        if (icon == null) return;
        Object tag = icon.getTag(R.id.imageView_tx_steps_gas_icon);
        if (tag instanceof AnimatorSet) {
            ((AnimatorSet) tag).cancel();
            icon.setTag(R.id.imageView_tx_steps_gas_icon, null);
        }
        icon.setAlpha(1f);
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setImageResource(R.drawable.gas_icon_selector);
    }
}

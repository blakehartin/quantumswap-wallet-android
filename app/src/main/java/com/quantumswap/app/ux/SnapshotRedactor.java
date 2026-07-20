package com.quantumswap.app.ux;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.WeakHashMap;

/**
 * Java port of iOS {@code UX/SnapshotRedactor.swift} that paints an
 * opaque branded cover over the activity content the moment the
 * activity loses foreground status, then removes it on resume.
 * <p>Why this exists: {@code FLAG_SECURE} (Part
 * J) already prevents Android from feeding the live screen into the
 * recents thumbnail, but on most OEMs that means the user sees a
 * black tile -- ugly and slightly suspicious. iOS's
 * {@code SnapshotRedactor} renders a branded cover, which (a) makes
 * the recents tile look intentional and (b) signals "this app is
 * deliberately hiding sensitive content" to the user.
 * <p>Threat model: a casual shoulder-surfer picking up the unlocked
 * device should not see the wallet's address strip or balance in the
 * recents carousel. The cover is added on
 * {@link Application.ActivityLifecycleCallbacks#onActivityPaused}
 * (which fires before the OS captures the recents thumbnail) and
 * removed on {@code onActivityResumed}.
 * <p>Caller install: call {@link #install(Application)} once from
 * {@code Application.onCreate()}. The redactor self-attaches to
 * every activity that has {@code FLAG_SECURE} set; activities
 * without {@code FLAG_SECURE} are left alone (they are public-data
 * surfaces by design).
 */
public final class SnapshotRedactor implements Application.ActivityLifecycleCallbacks {

    private final WeakHashMap<Activity, View> covers = new WeakHashMap<>();

    private SnapshotRedactor() { }

    public static void install(@NonNull Application app) {
        app.registerActivityLifecycleCallbacks(new SnapshotRedactor());
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) { }
    @Override public void onActivityStarted(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) {
        covers.remove(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        View cover = covers.remove(activity);
        if (cover == null) return;
        ViewGroup parent = (ViewGroup) cover.getParent();
        if (parent != null) parent.removeView(cover);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (!hasFlagSecure(activity)) return;
        if (covers.containsKey(activity)) return;

        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (root == null) return;

        FrameLayout cover = new FrameLayout(activity);
        // Quantum Violet app background (see values/colors.xml quantumBg).
        cover.setBackgroundColor(Color.parseColor("#050508"));
        cover.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout center = new LinearLayout(activity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        center.setLayoutParams(lp);

        TextView brand = new TextView(activity);
        brand.setText("QuantumSwap");
        brand.setTextSize(22);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(Color.WHITE);
        center.addView(brand);

        cover.addView(center);
        root.addView(cover);
        covers.put(activity, cover);
    }

    private static boolean hasFlagSecure(Activity activity) {
        try {
            int flags = activity.getWindow().getAttributes().flags;
            return (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}

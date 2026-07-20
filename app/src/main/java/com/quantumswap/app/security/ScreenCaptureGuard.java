package com.quantumswap.app.security;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Java port of iOS {@code Security/ScreenCaptureGuard.swift}.
 *
 * <p>Beyond {@code FLAG_SECURE} (which blocks screenshots and recents
 * thumbnails), iOS observes
 * {@code UIScreen.capturedDidChangeNotification} so that during an
 * active recording / AirPlay session the seed-display surfaces hide
 * their content and replace it with a "Seed phrase hidden because
 * the screen is being recorded or mirrored" message.
 *
 * <p>Android exposes a similar signal via {@link DisplayManager}'s
 * presentation displays + {@link Display#getFlags()} for the default
 * display. We poll the count of secondary displays on a short
 * cadence (the cheaper {@code OnDisplayChangedListener} surfaces
 * mirror/cast adds-and-removes) and fire {@link Listener#onCaptureStateChanged(boolean)}
 * to every observer.
 *
 * <p>API floor: presentation-display detection works back to API 17;
 * MediaProjection-active detection requires API 33+ so we
 * intentionally fall back to "best-effort" for older devices and
 * document the gap here so reviewers don't expect bullet-proof
 * coverage on legacy hardware.
 */
public final class ScreenCaptureGuard {

    public interface Listener {
        void onCaptureStateChanged(boolean captureActive);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean lastCaptureActive = false;
    private static volatile boolean installed = false;

    private ScreenCaptureGuard() { }

    public static void install(@NonNull final Context appContext) {
        if (installed) return;
        installed = true;
        DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return;

        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { recheck(appContext); }
            @Override public void onDisplayRemoved(int displayId) { recheck(appContext); }
            @Override public void onDisplayChanged(int displayId) { recheck(appContext); }
        };
        dm.registerDisplayListener(listener, MAIN);

        recheck(appContext);
    }

    public static void addListener(Listener l) {
        if (l == null) return;
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
        l.onCaptureStateChanged(lastCaptureActive);
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        LISTENERS.remove(l);
    }

    public static boolean isCaptureActive() { return lastCaptureActive; }

    private static void recheck(Context appContext) {
        boolean active = false;
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display[] displays = dm.getDisplays();
                if (displays != null) {
                    for (Display d : displays) {
                        if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                            // Any secondary display present means the
                            // primary screen content can be visible
                            // off-device (cast / wired mirror).
                            active = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignore) { }

        if (active != lastCaptureActive) {
            lastCaptureActive = active;
            for (Listener l : LISTENERS) {
                try { l.onCaptureStateChanged(active); } catch (Throwable ignore) { }
            }
        }
    }

    /**
     * Convenience for activities that want to react to capture-state
     * changes by hiding a sensitive view subtree. The activity is
     * responsible for unregistering on destroy.
     */
    public static Listener attachVisibilityToggle(final Activity activity, final android.view.View sensitive) {
        Listener l = new Listener() {
            @Override
            public void onCaptureStateChanged(boolean captureActive) {
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (sensitive == null) return;
                            sensitive.setVisibility(captureActive
                                    ? android.view.View.INVISIBLE
                                    : android.view.View.VISIBLE);
                        } catch (Throwable ignore) { }
                    }
                });
            }
        };
        addListener(l);
        return l;
    }
}

package com.quantumswap.app.utils;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import timber.log.Timber;

/**
 * Clipboard helper for sensitive values (seed phrases, private keys).
 *
 * <ul>
 *   <li>Marks the ClipData as sensitive on API 33+ so the clipboard preview
 *       does not leak the value to anyone glancing at the screen.</li>
 *   <li>Schedules an auto-clear of the clipboard after {@link #AUTO_CLEAR_MS}.
 *       If the user has copied something else in the meantime we leave that
 *       newer value alone, so we never clobber unrelated data.</li>
 * </ul>
 *
 * <p><b>Seed clipboard copy is OS-hardened only on API 33+.</b>
 * On API 29-32 (Android 10-12) the {@code IS_SENSITIVE} ClipDescription
 * extra does not exist, so a copied seed phrase is visible to vendor
 * clipboard managers, accessibility services, and clipboard-history
 * dumps for the entire 30-second auto-clear window. Callers that copy
 * a seed phrase or other long-term-secret material MUST gate the UI
 * affordance on {@link #isSeedClipboardCopyHardened()} and hide the
 * affordance on older OS versions; on those devices the user must
 * write the seed down by hand. Public information like wallet
 * addresses and transaction hashes is fine to copy on every API
 * level.</p>
 */
public final class SecureClipboard {

    private static final long AUTO_CLEAR_MS = 30_000L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private SecureClipboard() { }

    /**
     * True when the running Android version supports the
     * {@code android.content.extra.IS_SENSITIVE} ClipDescription extra
     * (Android 13 / API 33 and newer). When this returns {@code false}
     * the platform cannot mark a seed copy as private and seed-copy
     * UI affordances MUST be hidden.
     */
    public static boolean isSeedClipboardCopyHardened() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * Copy a sensitive value to the clipboard with the "sensitive" flag set
     * (API 33+) and schedule an automatic clear after 30 seconds.
     */
    public static void copySensitive(Context ctx, String label, CharSequence value) {
        copyInternal(ctx, label, value, true);
    }

    /**
     * Copy a wallet address with the "sensitive" flag set (API 33+)
     * so it does not show up in Android 13+ clipboard previews, but
     * <b>without</b> the auto-clear - addresses are public information
     * and clobbering them after 30s would hurt usability (users often
     * paste into another app after a delay).
     */
    public static void copyAddress(Context ctx, String label, CharSequence value) {
        copyInternal(ctx, label, value, false);
    }

    private static void copyInternal(Context ctx, String label, CharSequence value,
                                     boolean autoClear) {
        if (ctx == null || value == null) return;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;

        final ClipData clip = ClipData.newPlainText(label, value);

        if (Build.VERSION.SDK_INT >= 33) {
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle(1);
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                desc.setExtras(extras);
            }
        }

        cm.setPrimaryClip(clip);

        if (!autoClear) {
            return;
        }

        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        final String targetText = value.toString();

        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager m = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (m == null) return;
                    ClipData current = m.getPrimaryClip();
                    String currentText = extractText(current);
                    if (currentText != null && currentText.equals(targetText)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            m.clearPrimaryClip();
                        } else {
                            m.setPrimaryClip(ClipData.newPlainText("", ""));
                        }
                        com.quantumswap.app.Logger.d("SecureClipboard", "auto-cleared");
                    }
                } catch (Throwable t) {
                    com.quantumswap.app.Logger.w("SecureClipboard", "auto-clear", t);
                }
            }
        }, AUTO_CLEAR_MS);
    }

    @Nullable
    private static String extractText(@Nullable ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence cs = clip.getItemAt(0).getText();
        return cs == null ? null : cs.toString();
    }
}

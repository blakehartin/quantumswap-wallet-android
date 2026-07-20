package com.quantumswap.app.security;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import com.quantumswap.app.viewmodel.JsonViewModel;

/**
 * Policy + UI surface that consumes the {@link TamperGate.TamperReport}
 * produced by the probe layer. This class owns the user-facing
 * dialog, the "ignore-and-resume" persistence, and the integration
 * with the language pack.
 *
 * <p><b>Ignore-and-resume policy.</b> The wallet is non-custodial
 * and ships to power users who legitimately run rooted devices for
 * development. A blanket refuse-to-launch posture would be hostile
 * to those users without materially improving the security of the
 * average user (a determined attacker who has already rooted the
 * device can defeat any in-process check). The default policy is
 * therefore:
 * <ul>
 *   <li>On the first launch where the gate fires for a given
 *   {@link TamperGate.Severity}, surface a one-time dialog that
 *   explains the signal in plain language and offers two buttons:
 *   "Continue at my own risk" and "Close app".</li>
 *   <li>If the user chooses "Continue", we record an
 *   {@link android.content.SharedPreferences} entry keyed by the
 *   severity. Future launches re-run the probe, but if the signal
 *   matches the recorded "ignored" entry we proceed silently.</li>
 *   <li>If the user chooses "Close", we exit the activity. We do
 *   NOT wipe state or log them out; the next launch re-evaluates.</li>
 * </ul>
 *
 * <p><b>Why a separate file from {@link TamperGate}.</b> The probe
 * primitives must be unit-testable without a UI dependency, and the
 * UI layer must be rebrandable without re-touching the probe code.
 * Splitting along that seam keeps the security primitives free of
 * Android lifecycle / view dependencies and lets the UI layer
 * consume {@code Severity} as a plain enum.
 */
public final class TamperGatePolicy {

    /** Master switch. Flip to false in an emergency to disable the
     * dialog entirely while keeping the probe results computable for
     * telemetry. */
    public static final boolean K_TAMPER_GATE_ENABLED = true;

    private static final String PREFS_NAME = "tamper_gate";
    private static final String KEY_IGNORED_PREFIX = "ignored:";

    private TamperGatePolicy() { }

    /**
     * Apply the policy on top of a fresh probe run. Safe to call
     * from any {@link Activity#onResume()}; the dialog is
     * idempotent within the lifetime of the process.
     *
     * @param activity      hosting activity used to anchor the dialog
     *                      and to call {@link Activity#finish()} on
     *                      "Close app".
     * @param report        the probe outcome; may be {@code null}
     *                      in which case the policy treats it as
     *                      {@link TamperGate.Severity#CLEAN}.
     * @param vm            view-model for localized button labels and
     *                      title; may be {@code null} in which case
     *                      English fallbacks are used.
     */
    public static void apply(Activity activity,
                             TamperGate.TamperReport report,
                             JsonViewModel vm) {
        if (!K_TAMPER_GATE_ENABLED) return;
        if (activity == null || activity.isFinishing()) return;
        if (report == null || report.severity == TamperGate.Severity.CLEAN) return;

        if (hasUserIgnored(activity, report.severity)) return;

        String title = "Security check";
        String body = describe(report.severity);
        String continueLabel = "Continue at my own risk";
        String closeLabel = "Close app";
        if (vm != null) {
            try { closeLabel = safe(vm.getCloseByLangValues(), closeLabel); } catch (Throwable ignore) { }
        }

        final String severityKey = report.severity.name();
        final Context appCtx = activity.getApplicationContext();

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(body)
                .setCancelable(false)
                .setPositiveButton(continueLabel, (d, w) -> {
                    rememberIgnore(appCtx, severityKey);
                    d.dismiss();
                })
                .setNegativeButton(closeLabel, (d, w) -> {
                    d.dismiss();
                    try { activity.finish(); } catch (Throwable ignore) { }
                })
                .show();
    }

    private static String describe(TamperGate.Severity severity) {
        switch (severity) {
            case ROOT_SUSPECTED:
                return "This device appears to be rooted. The wallet's security model assumes "
                        + "the OS sandbox is intact. Continuing means you accept that an attacker "
                        + "with root could read your private keys.";
            case DEBUGGER_ATTACHED_IN_RELEASE:
                return "A debugger is attached to this app. Continuing means an attacker can "
                        + "read process memory, including your private keys, while you use the wallet.";
            case RUNTIME_TAMPER_DETECTED:
                return "The app's JavaScript bundle does not match the build-time hash, or a "
                        + "code-injection signal was detected. Continuing means signing primitives "
                        + "may have been replaced. We strongly recommend reinstalling from a trusted source.";
            default:
                return "A security check produced an unexpected result. Proceed with caution.";
        }
    }

    private static boolean hasUserIgnored(Context ctx, TamperGate.Severity severity) {
        if (ctx == null || severity == null) return false;
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_IGNORED_PREFIX + severity.name(), false);
    }

    private static void rememberIgnore(Context ctx, String severityKey) {
        if (ctx == null || severityKey == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_IGNORED_PREFIX + severityKey, true).apply();
    }

    private static String safe(String s, String fallback) {
        if (s == null) return fallback;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}

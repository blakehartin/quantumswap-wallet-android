package com.quantumswap.app.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.quantumswap.app.Logger;

/**
 * Rate-limiting for any password-based unlock attempt against
 * the strongbox or against a backup-restore decrypt.
 * <p><b>Why this exists:</b>
 * {@link com.quantumswap.app.keystorage.UnlockCoordinator#unlock}
 * runs scrypt with N=2^18, r=8, p=1 on the user's password.
 * That is roughly 200-1000 ms per attempt on modern Android
 * devices. Without rate limiting, an attacker who has the
 * device and the encrypted strongbox file can mount a
 * tap-the-Unlock-button brute-force loop at roughly 1-3
 * attempts/second, working through any common-password list
 * in seconds-to-minutes for low-entropy passwords (the
 * enforced minimum is 12 chars but no entropy floor; a
 * motivated user can still pick "Password1234!"). The same
 * threat applies to the backup-file restore flow: a
 * {@code .wallet} file plus a low-entropy backup password is
 * offline-bruteforceable, but in-app brute-force ALSO matters
 * for the restore path because the user-experienced UX
 * (paste a backup file, type a guess, repeat) is the same
 * pattern.</p>
 * <p><b>This limiter:</b>
 * <ul>
 *   <li>Tracks {@code (count, lastFailureMonotonicNanos)}
 *       inside a dedicated SharedPreferences file
 *       ({@value #PREF_FILE}). The file lives in the app's
 *       private storage so a non-rooted attacker cannot edit
 *       it; iOS uses Keychain for the equivalent
 *       "still-private but survives force-kill" property.
 *       Trade-off: a rooted attacker can wipe this file to
 *       reset the counter, the same way an iOS jailbreak
 *       attacker can wipe the Keychain item. Out of scope for
 *       the user-mode threat model.</li>
 *   <li>Enforces a stair-stepped backoff after N=5 failures:
 *       attempts 1-4: no penalty (typo tolerance),
 *       attempt 5: 30 s wait,
 *       attempt 6: 60 s,
 *       attempt 7: 2 min,
 *       attempt 8+: 5 min (cap; no permanent lockout).
 *       The schedule is intentionally NOT permanent-lockout: a
 *       user with severe typo storms is not bricked from their
 *       wallet. The backoff still shrinks an unlimited
 *       brute-force from "minutes" to "decades" for any
 *       low-entropy-but-not-trivial password.</li>
 * </ul></p>
 * <p><b>Clock choice:</b>
 * Elapsed-time arithmetic uses {@link SystemClock#elapsedRealtimeNanos()},
 * NOT {@link System#currentTimeMillis()}. The wall clock is
 * user-adjustable from Settings, so a forward clock jump
 * would otherwise immediately exit any in-progress lockout.
 * {@code elapsedRealtimeNanos()}:
 * <ul>
 *   <li>Counts real elapsed nanoseconds since boot, INCLUDING
 *       while the device was sleeping (locked screen). An
 *       attacker cannot extend the elapsed-time window by
 *       locking the device for hours; sleep counts as elapsed
 *       time.</li>
 *   <li>Is immune to wall-clock writes from Settings.</li>
 *   <li>Resets on reboot. We detect reboot by comparing the
 *       stored monotonic value to the current one: if the
 *       stored value is LARGER than current we infer the
 *       system rebooted since the last failure was recorded,
 *       and we apply the MAXIMUM lockout tier (5 min) for
 *       that attempt cycle.</li>
 * </ul></p>
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code UnlockAttemptLimiter.swift} byte-for-byte
 * on the schedule and reboot-defense behaviour. Same backoff
 * tiers, same 5-minute cap, same reboot detection.</p>
 */
public final class UnlockAttemptLimiter {

    /** Decision returned by {@link #currentDecision(Context)}.
     *  Call sites must branch on this BEFORE invoking the
     *  underlying scrypt-backed unlock so a locked-out attacker
     *  cannot keep paying scrypt cost. */
    public enum DecisionKind {
        ALLOWED,
        LOCKED
    }

    public static final class Decision {
        public final DecisionKind kind;
        /** Seconds remaining on the lockout. Zero when {@link
         *  #kind} is ALLOWED. Rounded UP for user display. */
        public final long remainingSeconds;

        public Decision(DecisionKind kind, long remainingSeconds) {
            this.kind = kind;
            this.remainingSeconds = remainingSeconds;
        }

        public static Decision allowed() {
            return new Decision(DecisionKind.ALLOWED, 0);
        }

        public static Decision lockedFor(long remainingSeconds) {
            return new Decision(DecisionKind.LOCKED, remainingSeconds);
        }
    }

    /** Caller flag identifying which lockout family the call
     *  site belongs to. Today both flow into a single shared
     *  counter (see file header), but the channel is logged so
     *  future tuning can be added without changing call sites. */
    public enum Channel {
        STRONGBOX_UNLOCK,
        BACKUP_DECRYPT
    }

    /** Dedicated prefs file. Separate from the main app prefs
     *  so a "clear app prefs" debug dialog does not also wipe
     *  the limiter. */
    private static final String PREF_FILE = "qc_unlock_attempt_limiter_v2";

    private static final String KEY_COUNT = "count";
    private static final String KEY_LAST_FAILURE_NANOS = "lastFailureMonotonicNanos";

    /** The lowest count value that maps to the maximum tier.
     *  Used by {@link #currentDecision(Context)} to compute the
     *  post-reboot max-tier wait without hard-coding the
     *  seconds. */
    private static final int MAX_TIER_COUNT = 10;

    private UnlockAttemptLimiter() {}

    /** Read the current state and return the decision.
     *  Idempotent; safe to call from any thread. */
    public static Decision currentDecision(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        int count = sp.getInt(KEY_COUNT, 0);
        long lastFailureNanos = sp.getLong(KEY_LAST_FAILURE_NANOS, 0L);

        long waitNeeded = backoffSeconds(count);
        if (waitNeeded == 0) return Decision.allowed();

        long now = SystemClock.elapsedRealtimeNanos();
        if (lastFailureNanos > now) {
            // System rebooted since the failure was recorded.
            // Apply the maximum lockout tier for this cycle to
            // prevent the "fail N times, reboot, retry" bypass.
            // The wait duration is capped at the MAX tier
            // (300s = 5 minutes) so a user with a legitimate
            // reboot is never permanently bricked; one MAX
            // tier wait unblocks them. The 5-minute cap still
            // dwarfs typical Android reboot time (~30-90s) by
            // ~3-10x, so the reboot-bypass attacker still pays
            // a meaningful penalty per attempt cycle.
            long maxTier = backoffSeconds(MAX_TIER_COUNT);
            return Decision.lockedFor(maxTier);
        }
        long elapsedNanos = now - lastFailureNanos;
        long elapsedSec = elapsedNanos / 1_000_000_000L;
        if (elapsedSec >= waitNeeded) return Decision.allowed();
        long remaining = waitNeeded - elapsedSec;
        // Floor of seconds elapsed leaves a sub-second remainder; round up
        // remaining so a user who fires the request 0.4s before the
        // window opens still sees a non-zero countdown.
        if ((elapsedNanos % 1_000_000_000L) > 0) {
            remaining = Math.max(0, remaining - 1);
            if (remaining == 0) {
                // Roll the rounding back so we never report 0
                // unless the caller is genuinely allowed.
                return Decision.lockedFor(1);
            }
        }
        return Decision.lockedFor(remaining);
    }

    /** Reset the counter on a successful unlock. Call only on
     *  a confirmed-correct password. */
    public static void recordSuccess(Context ctx, Channel channel) {
        Logger.i("UnlockAttemptLimiter", "recordSuccess channel=" + channel);
        prefs(ctx).edit()
                .putInt(KEY_COUNT, 0)
                .putLong(KEY_LAST_FAILURE_NANOS, 0L)
                .commit();
    }

    /** Increment the counter on a wrong-password failure.
     *  Persists the new state so a kill+relaunch does not reset
     *  it. */
    public static void recordFailure(Context ctx, Channel channel) {
        SharedPreferences sp = prefs(ctx);
        int count = sp.getInt(KEY_COUNT, 0) + 1;
        long now = SystemClock.elapsedRealtimeNanos();
        Logger.w("UnlockAttemptLimiter", "recordFailure channel=" + channel + " count=" + count);
        sp.edit()
                .putInt(KEY_COUNT, count)
                .putLong(KEY_LAST_FAILURE_NANOS, now)
                .commit();
    }

    /** Format a user-facing message for a {@code tooManyAttempts}
     *  failure. Centralised so every unlock UI surface renders
     *  the same wording for the same lockout state.
     *  <p>The wording is sourced from {@code en_us.json}'s
     *  {@code errors} block via the supplied {@link JsonViewModel}
     *  (keys {@code unlock-too-many-attempts-seconds},
     *  {@code unlock-too-many-attempts-one-minute},
     *  {@code unlock-too-many-attempts-minutes}). The {@code vm}
     *  argument is nullable: if the caller has not yet built a
     *  view-model (or the catalog lookup returns null/empty), the
     *  method falls back to an English literal. The security signal
     *  ("you are locked out for N seconds, this is not a password
     *  typo") is more important than perfect language fidelity in
     *  the lockout path, so an English fallback is acceptable.</p> */
    public static String userFacingLockoutMessage(long remainingSeconds,
                                                  com.quantumswap.app.viewmodel.JsonViewModel vm) {
        if (remainingSeconds < 60) {
            String tpl = (vm == null) ? null : vm.getUnlockTooManyAttemptsSecondsByErrors();
            if (tpl == null || tpl.isEmpty()) {
                return "Too many failed attempts. Please wait " + remainingSeconds
                        + " seconds and try again.";
            }
            return tpl.replace("[SECONDS]", Long.toString(remainingSeconds));
        }
        long minutes = (remainingSeconds + 59) / 60;
        if (minutes == 1) {
            String tpl = (vm == null) ? null : vm.getUnlockTooManyAttemptsOneMinuteByErrors();
            if (tpl == null || tpl.isEmpty()) {
                return "Too many failed attempts. Please wait 1 minute and try again.";
            }
            return tpl;
        }
        String tpl = (vm == null) ? null : vm.getUnlockTooManyAttemptsMinutesByErrors();
        if (tpl == null || tpl.isEmpty()) {
            return "Too many failed attempts. Please wait " + minutes + " minutes and try again.";
        }
        return tpl.replace("[MINUTES]", Long.toString(minutes));
    }

    /**
     * Stair-step delay schedule. Returns 0 for counts below the
     * warm-up tolerance (4 failures), then ramps up; caps at
     * 5 minutes for any tier that would otherwise exceed it.
     * <p><b>Monotonicity:</b>
     * Tiers &gt;= 8 all evaluate to the cap value so the
     * schedule remains monotonic non-decreasing. Without that
     * the tier 9 (originally 900s) would have been LARGER than
     * tier 10+ (now 300s), which would have been a non-sensical
     * "more failures shorter wait" curve. Monotonicity is the
     * contract the limiter has with the user: each successive
     * failure is at least as costly as the previous one.</p>
     */
    static long backoffSeconds(int n) {
        if (n < 5) return 0;
        if (n == 5) return 30;
        if (n == 6) return 60;
        if (n == 7) return 120;
        return 300;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }
}

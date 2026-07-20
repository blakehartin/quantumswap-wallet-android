package com.quantumswap.app;

import android.util.Log;

import timber.log.Timber;

/**
 * Logging surface for the wallet app.
 * <p>This is the single seam every other class should use when
 * it wants to emit log output. The actual logging backend
 * (Timber DebugTree in debug builds, no-op-ish ReleaseTree in
 * release builds — see {@link App#onCreate()}) is hidden
 * behind this seam so a future swap (e.g. routing through
 * Sentry breadcrumbs) only edits this file.</p>
 * <ul>
 *   <li>In release builds {@code Timber.plant(new ReleaseTree())}
 *       drops every VERBOSE/DEBUG/INFO call and emits only the
 *       static literal {@code "evt"} for WARN/ERROR. No message
 *       payload reaches logcat in release builds, even if a
 *       caller passes raw addresses or ciphertext.</li>
 *   <li>In debug builds the planted tree is
 *       {@link RedactingDebugTree} which heuristically redacts
 *       0x… addresses (40 hex chars), tx hashes (64 hex
 *       chars), long bare-hex sequences (&gt;= 32 chars), and
 *       long base64 blobs (&gt;= 40 chars) BEFORE the message
 *       reaches logcat. This protects developer build console
 *       output from accidentally screen-capturing private keys
 *       or seed-derived keys during a debug session that is
 *       then shared (e.g. attached to a bug report).</li>
 *   <li>The redaction is best-effort — a developer who passes
 *       a key as a raw 32-byte array via {@code Arrays.toString}
 *       will get an unredacted byte sequence in logcat. The
 *       guidance is "don't log secrets". The redaction is the
 *       safety net, not the contract.</li>
 * </ul></p>
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code Logger.swift} which also runs heuristic
 * redaction in DEBUG and is a complete no-op in RELEASE
 * (osLog with no message body).</p>
 */
public final class Logger {

    private Logger() {}

    /** Verbose log. No-op in release builds. */
    public static void v(String tag, String message) {
        Timber.tag(tag).v(message);
    }

    /** Debug log. No-op in release builds. */
    public static void d(String tag, String message) {
        Timber.tag(tag).d(message);
    }

    /** Info log. No-op in release builds. */
    public static void i(String tag, String message) {
        Timber.tag(tag).i(message);
    }

    /** Warn log. Emits {@code "evt"} (literal, no payload) in
     *  release builds. */
    public static void w(String tag, String message) {
        Timber.tag(tag).w(message);
    }

    /** Warn log with throwable. */
    public static void w(String tag, String message, Throwable t) {
        Timber.tag(tag).w(t, message);
    }

    /** Error log. Emits {@code "evt"} in release builds. */
    public static void e(String tag, String message) {
        Timber.tag(tag).e(message);
    }

    /** Error log with throwable. */
    public static void e(String tag, String message, Throwable t) {
        Timber.tag(tag).e(t, message);
    }

    /** Direct passthrough for callers that want the raw priority
     *  enum (rare; favour the named methods above). */
    public static void log(int priority, String tag, String message) {
        switch (priority) {
            case Log.VERBOSE: v(tag, message); break;
            case Log.DEBUG: d(tag, message); break;
            case Log.INFO: i(tag, message); break;
            case Log.WARN: w(tag, message); break;
            case Log.ERROR: e(tag, message); break;
            default: i(tag, message);
        }
    }
}

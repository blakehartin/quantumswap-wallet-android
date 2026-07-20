package com.quantumswap.app;

import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * Timber tree planted in DEBUG builds only. Wraps Timber's
 * standard {@link Timber.DebugTree} and runs heuristic
 * redaction on the message string before delegating.
 * <p><b>What it redacts:</b>
 * <ul>
 *   <li>0x-prefixed 40-hex-char addresses → {@code 0x<addr-redacted>}</li>
 *   <li>0x-prefixed 64-hex-char hashes → {@code 0x<txhash-redacted>}</li>
 *   <li>Long bare hex sequences (&gt;= 32 chars) → {@code <hex-redacted>}</li>
 *   <li>Long base64 blobs (&gt;= 40 of [A-Za-z0-9+/=]) → {@code <b64-redacted>}</li>
 * </ul>
 * The order matters: we substitute the most specific patterns
 * first (40/64 hex addresses) so a 64-char hex string gets
 * the more informative {@code &lt;txhash-redacted&gt;} label
 * instead of the generic {@code &lt;hex-redacted&gt;}.</p>
 * <p><b>Why heuristic and not strict:</b>
 * The release build (see {@link App#onCreate()}) is the only
 * configuration that ships to end users; in release the
 * ReleaseTree drops the message payload entirely, so this
 * redaction is purely a developer-ergonomic safety net. We
 * deliberately accept the risk of false positives (an unrelated
 * 64-char hex string gets relabelled as {@code <txhash-
 * redacted>}) in exchange for catching the common-case leak
 * patterns without forcing every caller to remember to
 * pre-redact.</p>
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code RedactingDebugLogger.swift} which uses
 * the same regex set for DEBUG-build redaction.</p>
 */
public final class RedactingDebugTree extends Timber.DebugTree {

    /** 0x-prefixed 64-hex-char tx hash. Match BEFORE the
     *  40-hex-char address pattern so a 64-char hex doesn't
     *  partial-match as an address. */
    private static final Pattern TX_HASH = Pattern.compile(
            "0x[0-9a-fA-F]{64}");

    /** 0x-prefixed 40-hex-char address. */
    private static final Pattern ADDRESS = Pattern.compile(
            "0x[0-9a-fA-F]{40}");

    /** Bare hex sequence of 32+ chars (covers private keys,
     *  ciphertext blobs, etc. that are NOT 0x-prefixed). */
    private static final Pattern LONG_HEX = Pattern.compile(
            "(?<![0-9a-fA-F])[0-9a-fA-F]{32,}(?![0-9a-fA-F])");

    /** Base64 blob of 40+ chars. The {@code [+/=]} class
     *  excludes ordinary identifiers (which lack +, /, =) so
     *  this pattern is unlikely to false-positive on Java
     *  symbol names. */
    private static final Pattern LONG_BASE64 = Pattern.compile(
            "[A-Za-z0-9+/]{40,}={0,2}");

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        String redacted = redact(message);
        super.log(priority, tag, redacted, t);
    }

    /** Apply the redaction pipeline. Visible for unit testing. */
    public static String redact(String message) {
        if (message == null || message.isEmpty()) return message;
        String s = message;
        s = TX_HASH.matcher(s).replaceAll("0x<txhash-redacted>");
        s = ADDRESS.matcher(s).replaceAll("0x<addr-redacted>");
        s = LONG_HEX.matcher(s).replaceAll("<hex-redacted>");
        // Run base64 last so the substitution doesn't eat the
        // already-redacted placeholder. Placeholders contain
        // <,> which are not in the base64 alphabet, so they
        // are safe.
        s = LONG_BASE64.matcher(s).replaceAll("<b64-redacted>");
        return s;
    }
}

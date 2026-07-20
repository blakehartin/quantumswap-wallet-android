package com.quantumswap.app.networking;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.quantumswap.app.utils.GlobalMethods;

import java.util.regex.Pattern;

/**
 * Single source of truth for every URL the app composes from a base
 * + an attacker-influenceable string segment (a wallet address, a
 * token-contract address, a transaction hash). Java port of the iOS
 * {@code Networking/UrlBuilder.swift} helper.
 * <p>Why this exists: the app composes
 * block-explorer URLs by direct string concatenation:
 * <pre>
 * BLOCK_EXPLORER_URL +
 *   "/account/{address}/txn/page".replace("{address}", raw)
 * </pre>
 * <p>{@code raw} flows in from many sources -- scan-API JSON, QR
 * scans, pasteboard, typed input. None are constrained to "0x + 64
 * hex" by the call sites. A scan-API response with
 * {@code contractAddress: "0xdead/../../malicious?phish="} composes
 * a URL that, when passed to {@code Intent.ACTION_VIEW}, pivots the
 * user into the system browser at an attacker-chosen origin. The
 * browser then renders a phishing page that asks for the user's
 * seed words.
 * <p>This helper:
 * <ol>
 *   <li>Validates the substitute against the strict regex for its
 *   type ({@code ^0x[0-9a-fA-F]{64}$} -- QuantumCoin addresses and
 *   transaction hashes are both 32-byte / 64-hex-char 0x-prefixed
 *   values).</li>
 *   <li>Percent-encodes the substitute for safety even after the
 *   regex passes (defense-in-depth).</li>
 *   <li>Returns {@code null} on any validation failure so the caller
 *   falls back to a no-op (the user sees nothing happen rather than
 *   being pivoted to the browser).</li>
 * </ol>
 * <p>Lint contract: every call to {@code .replace("{address}", ...)}
 * and {@code .replace("{txhash}", ...)} outside this file is a
 * build-blocking review failure. The {@code UrlBuilderLockdownTest}
 * grep-checks for that pattern.
 */
public final class UrlBuilder {

    /** 0x-prefixed 32-byte hex string. Same shape as iOS. */
    private static final Pattern HEX_32B_0X = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    private UrlBuilder() { }

    /** True iff {@code s} is a 0x-prefixed 32-byte (64 hex char) value. */
    public static boolean isValidAddress(@Nullable String s) {
        return s != null && HEX_32B_0X.matcher(s).matches();
    }

    /** True iff {@code s} is a 0x-prefixed 32-byte transaction hash. */
    public static boolean isValidTxHash(@Nullable String s) {
        return s != null && HEX_32B_0X.matcher(s).matches();
    }

     /**
     * Build the full block-explorer URL for an account/contract address.
     * Returns {@code null} if {@code address} fails strict regex
     * validation OR if the URL cannot be parsed.
     */
    @Nullable
    public static Uri blockExplorerAccountUrl(@Nullable String address) {
        return substituted(
                GlobalMethods.BLOCK_EXPLORER_URL,
                GlobalMethods.BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL,
                "{address}",
                address,
                /* validator */ UrlBuilder::isValidAddress);
    }

     /**
     * Build the full block-explorer URL for a transaction hash. Returns
     * {@code null} on validation failure.
     */
    @Nullable
    public static Uri blockExplorerTxUrl(@Nullable String txHash) {
        return substituted(
                GlobalMethods.BLOCK_EXPLORER_URL,
                GlobalMethods.BLOCK_EXPLORER_TX_HASH_URL,
                "{txhash}",
                txHash,
                /* validator */ UrlBuilder::isValidTxHash);
    }

     /**
     * Build a relative API path (no scheme/host) with a strictly
     * validated address segment. Returns the path string (e.g.
     * {@code /account/0xabc...}) or {@code null} if {@code address}
     * fails validation. Callers compose the full URL via the scan-API
     * client which prefixes the base.
     */
    @Nullable
    public static String apiPath(String template, @Nullable String address) {
        if (template == null || !isValidAddress(address)) return null;
        return template.replace("{address}", percentEncodePathSegment(address));
    }

    @FunctionalInterface
    private interface Validator {
        boolean isValid(@Nullable String value);
    }

    @Nullable
    private static Uri substituted(@Nullable String base,
                                   @Nullable String template,
                                   String placeholder,
                                   @Nullable String value,
                                   Validator validator) {
        String composed = composedString(base, template, placeholder, value, validator);
        if (composed == null) return null;
        try {
            return Uri.parse(composed);
        } catch (Throwable t) {
            return null;
        }
    }

     /**
     * Same composition contract as {@link #substituted} but returns
     * the raw URL string. Exposed package-private so the
     * {@code UrlBuilderHostInvariantTest} can pin the post-
     * substitution host invariant without depending on the
     * {@link Uri} stub being available on the JVM unit-test
     * classpath.
     */
    @Nullable
    static String composedString(@Nullable String base,
                                 @Nullable String template,
                                 String placeholder,
                                 @Nullable String value,
                                 Validator validator) {
        if (base == null || base.isEmpty()) return null;
        if (template == null || template.isEmpty()) return null;
        if (!validator.isValid(value)) return null;
        // Defense-in-depth percent-encoding: strict regex already
        // ruled out the dangerous characters, but the encode step
        // makes the invariant true at the URL-spec layer.
        return base + template.replace(placeholder, percentEncodePathSegment(value));
    }

     /**
     * Test-only String accessors that bypass the Android {@link Uri}
     * parser. Production code paths should keep using
     * {@link #blockExplorerAccountUrl(String)} /
     * {@link #blockExplorerTxUrl(String)}.
     */
    @Nullable
    static String blockExplorerAccountUrlString(@Nullable String address) {
        return composedString(
                GlobalMethods.BLOCK_EXPLORER_URL,
                GlobalMethods.BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL,
                "{address}",
                address,
                UrlBuilder::isValidAddress);
    }

    @Nullable
    static String blockExplorerTxUrlString(@Nullable String txHash) {
        return composedString(
                GlobalMethods.BLOCK_EXPLORER_URL,
                GlobalMethods.BLOCK_EXPLORER_TX_HASH_URL,
                "{txhash}",
                txHash,
                UrlBuilder::isValidTxHash);
    }

     /**
     * Pure-Java percent-encoder for a URL path segment. Behaviorally
     * identical to {@code android.net.Uri.encode(s, "")} for any
     * input that has already passed our regex validators (which
     * restrict to {@code [0-9a-fA-Fx]} -- all members of the
     * unreserved-character set, so encoding is the identity).
     * <p>Implemented in pure Java (not via {@link Uri#encode}) so
     * unit tests that exercise the composer do not require the
     * Android {@link Uri} class to be mocked.
     */
    private static String percentEncodePathSegment(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // RFC 3986 unreserved set: ALPHA / DIGIT / "-" / "." / "_" / "~"
            boolean unreserved = (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') ||
                    c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append(c);
            } else if (c < 0x80) {
                appendHexByte(sb, (byte) c);
            } else {
                // Encode multi-byte UTF-8 sequence; defensive for
                // future call sites even though current validators
                // confine input to ASCII unreserved chars.
                byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : utf8) appendHexByte(sb, b);
            }
        }
        return sb.toString();
    }

    private static void appendHexByte(StringBuilder sb, byte b) {
        sb.append('%');
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
    }
}

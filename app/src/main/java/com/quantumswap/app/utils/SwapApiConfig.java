package com.quantumswap.app.utils;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-release Swap Read API configuration (web app releases.ts /
 * sanitize.ts): the built-in defaults, the URL sanitiser and the dexId
 * rule. Pure; shared by {@link ReleaseStore} and the Releases screen.
 * The bridge applies the same rules to the payload it receives.
 */
public final class SwapApiConfig {

    private SwapApiConfig() { }

    public static final String DEFAULT_API_URL = "https://api.quantumswap.com";
    public static final String DEFAULT_DEX_ID = "quantumswap-beta2";
    public static final int MAX_URL_LEN = 200;

    private static final Pattern URL = Pattern.compile(
            "^(https?)://([a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\])(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?$");
    private static final Pattern DEX_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** http(s) origin + path only (no credentials, query or fragment), no
     *  trailing slash, at most {@link #MAX_URL_LEN} characters; "" when
     *  the input is not acceptable. */
    public static String sanitizeUrl(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_URL_LEN) return "";
        Matcher m = URL.matcher(s);
        if (!m.matches()) return "";
        String path = m.group(4) == null ? "" : m.group(4).replaceAll("/+$", "");
        return m.group(1) + "://" + m.group(2) + (m.group(3) == null ? "" : m.group(3)) + path;
    }

    public static boolean isValidDexId(@Nullable String s) {
        return s != null && DEX_ID.matcher(s).matches();
    }
}

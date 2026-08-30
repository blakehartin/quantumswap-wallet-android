package com.quantumswap.app.utils;

import android.content.Context;

import com.quantumswap.app.viewmodel.JsonViewModel;

import org.json.JSONObject;

/**
 * Transient toast for a Swap Read API request that failed right before
 * the bridge fell back to the chain: the bridge reports the failed
 * request once as {@code apiFallback} (kind, HTTP status, server
 * message) on the result it then serves from RPC. Deduplicated for a few
 * seconds so per-keystroke quotes do not stack toasts (the bridge's
 * breaker keeps the API quiet for 30 s after repeated outages anyway).
 */
public final class SwapApiToast {

    private SwapApiToast() { }

    private static final long DEDUPE_MS = 5000;
    private static String lastMessage;
    private static long lastShownAt;

    public static void showIfFallback(Context context, JsonViewModel vm, JSONObject data) {
        if (context == null || vm == null || data == null) return;
        JSONObject fallback = data.optJSONObject("apiFallback");
        if (fallback == null) return;
        String detail = sanitize(fallback.optString("detail", ""));
        if (detail.isEmpty()) detail = sanitize(fallback.optString("kind", "error"));
        String message = vm.lang("swap-api-fallback-toast",
                "Swap Read API unavailable ([DETAIL]); using RPC.").replace("[DETAIL]", detail);
        long now = System.currentTimeMillis();
        synchronized (SwapApiToast.class) {
            if (message.equals(lastMessage) && now - lastShownAt < DEDUPE_MS) return;
            lastMessage = message;
            lastShownAt = now;
        }
        // Not the legacy ShowToast helper: it cancels the toast at 600 ms.
        // A plain LENGTH_LONG toast stays up ~3.5 s, long enough to read
        // the HTTP code / server message.
        final Context appCtx = context.getApplicationContext();
        Runnable show = () -> android.widget.Toast.makeText(appCtx, message, android.widget.Toast.LENGTH_LONG).show();
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show.run();
        else new android.os.Handler(android.os.Looper.getMainLooper()).post(show);
    }

    static String sanitize(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}

package com.quantumswap.app.bridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import com.quantumswap.app.BuildConfig;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

public class WebViewManager
{
    private static final String TAG = "WebViewManager";
    private static final String BRIDGE_URL = "https://appassets.androidplatform.net/assets/bridge.html";
    private static final String JS_INTERFACE_NAME = "AndroidBridge";

    private static volatile WebViewManager instance;

    /**
     * Hard cap on the number of staged payloads. Under normal
     * operation the bridge round-trip is sub-second so this should hold
     * at most a handful of entries; a full map indicates either a stuck
     * WebView or an unbounded producer and must not be allowed to grow.
     */
    private static final int MAX_PENDING_PAYLOADS = 64;
    /** Staged payload TTL. */
    private static final long PENDING_PAYLOAD_TTL_MS = 60_000L;
    /** Sweeper cadence while any payload is live. */
    private static final long PENDING_SWEEP_INTERVAL_MS = 30_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, BridgeCallback> pendingCallbacks = new ConcurrentHashMap<>();
    /**
     * Pull-model payload storage. Sensitive bridge arguments
     * (passwords, private keys, seed phrases) are staged here keyed by
     * requestId and pulled from the WebView via
     * {@link #getPendingPayload(String)}. This keeps them out of the
     * {@code evaluateJavascript} script strings entirely.
     *
     * <p>Each entry carries an enqueue timestamp so expired entries
     * can be swept. {@link #storePendingPayload} enforces a hard size
     * cap so a malfunctioning WebView cannot hold sensitive strings
     * in memory indefinitely.</p>
     */
    private final ConcurrentHashMap<String, PendingPayload> pendingPayloads = new ConcurrentHashMap<>();
    private final AtomicBoolean sweeperScheduled = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private WebView webView;
    private Context appContext;

    private WebViewManager()
    {
    }

    public static WebViewManager getInstance(@NonNull Context context)
    {
        if (instance == null)
        {
            synchronized (WebViewManager.class)
            {
                if (instance == null)
                {
                    instance = new WebViewManager();
                    instance.initialize(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initialize(@NonNull Context context)
    {
        this.appContext = context;

        if (Looper.myLooper() == Looper.getMainLooper())
        {
            createWebView();
        }
        else
        {
            mainHandler.post(this::createWebView);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView()
    {
        webView = new WebView(appContext);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // The bridge bundle and bridge.html do not use localStorage,
        // sessionStorage, or IndexedDB (verified with a repo-wide grep).
        // Disabling DOM storage removes an unnecessary persistence surface
        // and prevents any future dependency from quietly writing sensitive
        // strings into /data/data/.../app_webview storage.
        settings.setDomStorageEnabled(false);
        // Lock this WebView to https://appassets.androidplatform.net
        // and deny every other loader path. The bridge HTML/JS is served
        // via WebViewAssetLoader; there is no need to read from file://,
        // content://, or let JavaScript escape the origin sandbox.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(appContext))
                .build();

        webView.setWebViewClient(new WebViewClient()
        {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request)
            {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url)
            {
                super.onPageFinished(view, url);
                if (BRIDGE_URL.equals(url))
                {
                    ready.set(true);
                    readyLatch.countDown();
                    com.quantumswap.app.Logger.d(TAG, "Bridge WebView ready");
                }
            }

            // (Android, mirrors iOS JsEngine.recordLoadFailure):
            // capture WebView load failures into a process-visible
            // string so callers blocked on isReady()/waitUntilReady
            // can surface the ACTUAL underlying cause to the user
            // ("WebView net::ERR_NAME_NOT_RESOLVED ...") instead of
            // a generic "bridge not ready" timeout that masks every
            // possible failure mode.
            //
            // Sub-resource fetches (favicons, downstream relay calls
            // from the JS bundle, etc.) are NOT bridge-load failures
            // and must not pollute lastLoadFailureDescription -- a
            // transient DNS hiccup on a relay would otherwise mask
            // a later real bridge failure for any caller reading
            // getLastLoadFailureDescription(). In debug builds we
            // still surface them via Logger.d so developers can see
            // what the WebView is actually reaching for.
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                         android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                String url = (request != null && request.getUrl() != null)
                        ? request.getUrl().toString() : "(unknown)";
                String msg = (error != null ? "code=" + error.getErrorCode()
                        + " desc=" + error.getDescription() : "unknown");
                if (BRIDGE_URL.equals(url)) {
                    recordLoadFailure("onReceivedError(url=" + url + " " + msg + ")");
                } else if (BuildConfig.DEBUG) {
                    com.quantumswap.app.Logger.d(TAG,
                            "WebView sub-resource failure: onReceivedError(url="
                                    + url + " " + msg + ")");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                             android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                String url = (request != null && request.getUrl() != null)
                        ? request.getUrl().toString() : "(unknown)";
                String msg = errorResponse == null ? "null"
                        : ("status=" + errorResponse.getStatusCode()
                            + " reason=" + errorResponse.getReasonPhrase());
                if (BRIDGE_URL.equals(url)) {
                    recordLoadFailure("onReceivedHttpError(url=" + url + " " + msg + ")");
                } else if (BuildConfig.DEBUG) {
                    com.quantumswap.app.Logger.d(TAG,
                            "WebView sub-resource failure: onReceivedHttpError(url="
                                    + url + " " + msg + ")");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                            android.net.http.SslError error) {
                // Refuse the request and record. Do NOT proceed.
                if (handler != null) handler.cancel();
                recordLoadFailure("onReceivedSslError(" + (error != null ? error.toString() : "null") + ")");
            }
        });

        webView.setWebChromeClient(new WebChromeClient()
        {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm)
            {
                // In release builds, swallow JS console output so
                // sensitive strings inside uncaught JS errors can never
                // leak to logcat. In debug, route through Timber so
                // ReleaseTree cannot intercept anything by accident.
                if (BuildConfig.DEBUG)
                {
                    switch (cm.messageLevel())
                    {
                        case ERROR:
                            com.quantumswap.app.Logger.e(TAG,
                                    "JS ERROR: " + cm.message()
                                            + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
                            break;
                        case WARNING:
                            com.quantumswap.app.Logger.w(TAG,
                                    "JS WARN: " + cm.message()
                                            + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
                            break;
                        default:
                            com.quantumswap.app.Logger.d(TAG,
                                    "JS LOG: " + cm.message()
                                            + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
                            break;
                    }
                }
                return true;
            }
        });

        webView.addJavascriptInterface(this, JS_INTERFACE_NAME);
        // Refuse to even load the bridge if the on-disk
        // bundle bytes do not match the build-time pinned digest.
        // The bridge.html file references quantumswap-bundle.js by
        // relative URL, so the WebView's asset loader will read the
        // exact same bytes BundleIntegrity just hashed; verifying
        // here closes the "swap the asset, re-sign" attack class
        // before the JS context ever sees the tampered bytes.
        try {
            com.quantumswap.app.security.BundleIntegrity.verifyOrFail(appContext);
        } catch (com.quantumswap.app.security.BundleIntegrity.BundleIntegrityException e) {
            com.quantumswap.app.Logger.e(TAG, "Refusing to load JS bridge: " + e.getMessage());
            // Do not call loadUrl(); leave the bridge in the
            // not-ready state. Callers see isReady()==false and
            // surface the per-call error path. The TamperGate
            // bootstrap surfaced the user-facing dialog already.
            return;
        }
        webView.loadUrl(BRIDGE_URL);
    }

    public boolean isReady()
    {
        return ready.get();
    }

    public boolean waitUntilReady(long timeoutSeconds)
    {
        if (ready.get()) return true;
        try
        {
            return readyLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Process-visible last load failure description. Set by
     * the WebViewClient {@code onReceived*} callbacks; consumed by
     * the "bridge not ready" timeout error path so the user sees the
     * real cause (DNS failure, SSL pin mismatch, asset 404, ...)
     * instead of a generic timeout.
     */
    private static volatile String lastLoadFailureDescription;

    private void recordLoadFailure(String description) {
        com.quantumswap.app.Logger.e(TAG, "WebView load failure: " + description);
        lastLoadFailureDescription = description;
    }

    /** Last WebView load failure, or {@code null} if none observed. */
    public static String getLastLoadFailureDescription() {
        return lastLoadFailureDescription;
    }

    public void evaluateJavascript(@NonNull String script, @Nullable ValueCallback<String> callback)
    {
        if (Looper.myLooper() == Looper.getMainLooper())
        {
            if (webView != null)
            {
                webView.evaluateJavascript(script, callback);
            }
        }
        else
        {
            mainHandler.post(() ->
            {
                if (webView != null)
                {
                    webView.evaluateJavascript(script, callback);
                }
            });
        }
    }

    public void registerCallback(@NonNull String requestId, @NonNull BridgeCallback callback)
    {
        pendingCallbacks.put(requestId, callback);
    }

    /**
     * Stage a JSON payload that the WebView can later retrieve via
     * {@link #getPendingPayload(String)}. Used to avoid inlining
     * sensitive arguments into {@code evaluateJavascript}
     * script strings.
     *
     * <p>Enforces a hard size cap and sweeps expired entries so
     * that sensitive strings cannot accumulate in memory indefinitely
     * if the WebView side never consumes them.</p>
     */
    public void storePendingPayload(@NonNull String requestId, @NonNull String jsonPayload)
    {
        long now = SystemClock.elapsedRealtime();
        if (pendingPayloads.size() >= MAX_PENDING_PAYLOADS)
        {
            sweepExpired(now);
            if (pendingPayloads.size() >= MAX_PENDING_PAYLOADS)
            {
                throw new IllegalStateException(
                        "pending payload map full; refusing to stage new entry");
            }
        }
        pendingPayloads.put(requestId, new PendingPayload(jsonPayload, now));
        schedulePeriodicSweep();
    }

    /**
     * Called by {@code QuantumSwapJSBridge.blockingCall} on the
     * timeout / error path so sensitive staged payloads that the
     * JavaScript side never pulled do not linger in the map.
     */
    public void removePendingPayload(@NonNull String requestId)
    {
        pendingPayloads.remove(requestId);
    }

    /**
     * Exposes the build-type to the JS bridge so diagnostic
     * {@code console.*} output can be gated off in release.
     */
    @JavascriptInterface
    public boolean isDebug()
    {
        return BuildConfig.DEBUG;
    }

    /**
     * JavaScript-accessible pull point for sensitive payloads. Each
     * requestId is single-use: calling this removes the entry so a
     * replay on a stale id returns an empty string. Expired entries
     * (older than {@link #PENDING_PAYLOAD_TTL_MS}) are also rejected.
     */
    @JavascriptInterface
    public String getPendingPayload(String requestId)
    {
        if (requestId == null) return "";
        PendingPayload entry = pendingPayloads.remove(requestId);
        if (entry == null) return "";
        long age = SystemClock.elapsedRealtime() - entry.enqueuedAtMs;
        if (age > PENDING_PAYLOAD_TTL_MS) return "";
        return entry.jsonPayload;
    }

    private void sweepExpired(long nowMs)
    {
        Iterator<Map.Entry<String, PendingPayload>> it = pendingPayloads.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, PendingPayload> e = it.next();
            if (nowMs - e.getValue().enqueuedAtMs > PENDING_PAYLOAD_TTL_MS)
            {
                it.remove();
            }
        }
    }

    private void schedulePeriodicSweep()
    {
        if (!sweeperScheduled.compareAndSet(false, true)) return;
        mainHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                sweepExpired(SystemClock.elapsedRealtime());
                if (!pendingPayloads.isEmpty())
                {
                    mainHandler.postDelayed(this, PENDING_SWEEP_INTERVAL_MS);
                }
                else
                {
                    sweeperScheduled.set(false);
                }
            }
        }, PENDING_SWEEP_INTERVAL_MS);
    }

    private static final class PendingPayload
    {
        final String jsonPayload;
        final long enqueuedAtMs;

        PendingPayload(String jsonPayload, long enqueuedAtMs)
        {
            this.jsonPayload = jsonPayload;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    @JavascriptInterface
    public void onResult(String requestId, String jsonResult)
    {
        com.quantumswap.app.Logger.d(TAG, "onResult requestId=" + requestId);
        BridgeCallback callback = pendingCallbacks.remove(requestId);
        if (callback != null)
        {
            try
            {
                org.json.JSONObject json = new org.json.JSONObject(jsonResult);
                if (json.optBoolean("success", false))
                {
                    callback.onResult(jsonResult);
                }
                else
                {
                    String error = json.optString("error", "Unknown bridge error");
                    callback.onError(error);
                }
            }
            catch (org.json.JSONException e)
            {
                callback.onResult(jsonResult);
            }
        }
    }

    @JavascriptInterface
    public void onError(String requestId, String error)
    {
        com.quantumswap.app.Logger.e(TAG, "onError requestId=" + requestId + " error=" + error);
        BridgeCallback callback = pendingCallbacks.remove(requestId);
        if (callback != null)
        {
            callback.onError(error);
        }
    }

    public void destroy()
    {
        ready.set(false);
        pendingCallbacks.clear();
        pendingPayloads.clear();
        mainHandler.removeCallbacksAndMessages(null);
        sweeperScheduled.set(false);

        mainHandler.post(() ->
        {
            if (webView != null)
            {
                webView.removeJavascriptInterface(JS_INTERFACE_NAME);
                webView.stopLoading();
                webView.destroy();
                webView = null;
            }
        });

        synchronized (WebViewManager.class)
        {
            instance = null;
        }
    }
}

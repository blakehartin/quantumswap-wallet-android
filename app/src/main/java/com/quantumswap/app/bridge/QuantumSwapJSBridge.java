package com.quantumswap.app.bridge;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-side facade over the JavaScript bridge hosted in {@code bridge.html}.
 *
 * <p><b>Why the wallet crypto lives in a WebView/JS SDK (H-02, architectural decision):</b></p>
 * <p>The bundle at {@code webview-sdk-bundle/src/index.js} is the single shared
 * cryptographic implementation for the entire QuantumCoin ecosystem - Android,
 * iOS, desktop, and browser surfaces all talk to the same SDK. Hosting that
 * SDK once inside a WebView (instead of re-implementing BIP-39 / signing /
 * scrypt natively per platform) eliminates implementation drift between
 * clients and keeps the blast radius of any crypto bug to a single fix that
 * ships to every client in lock-step. This choice is deliberate and is not
 * going to be reversed for a pure-native crypto path.</p>
 *
 * <p>The residual risk (a malicious payload reaching the JS runtime) is
 * mitigated by several defence layers already in place:</p>
 * <ul>
 *     <li>The pull-model described below, so sensitive arguments never
 *         appear in the injected JS script string.</li>
 *     <li>{@link #escapeForJs(String)} hardened against control characters,
 *         line/paragraph separators, and JS string-literal breakers.</li>
 *     <li>DOM storage and file access disabled on the hosting WebView
 *         (see {@link WebViewManager#configureWebViewSettings}).</li>
 *     <li>Release-build log redaction via Timber so JS console / error
 *         output never reaches logcat.</li>
 * </ul>
 *
 * <p>Two transport models are used:</p>
 * <ul>
 *     <li><b>Push</b> - small, non-sensitive arguments are interpolated
 *         directly into the {@code evaluateJavascript} script string
 *         after being escaped with {@link #escapeForJs(String)}. Used
 *         for addresses, chain ids, RPC endpoints, etc.</li>
 *     <li><b>Pull</b> - sensitive arguments (passwords,
 *         private keys, seed phrases) are never written into the
 *         script string. They are staged as a JSON payload in
 *         {@link WebViewManager#storePendingPayload(String, String)}
 *         and the JavaScript handler pulls them back through the
 *         {@code AndroidBridge.getPendingPayload(requestId)}
 *         {@code @JavascriptInterface} right before use.</li>
 * </ul>
 */
public class QuantumSwapJSBridge {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final WebViewManager webViewManager;
    private final Handler mainHandler;

    public QuantumSwapJSBridge(WebViewManager webViewManager) {
        this.webViewManager = webViewManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ---- Async methods ----

    public String initializeAsync(int chainId, String rpcEndpoint, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.initialize('" + requestId + "', "
                + chainId + ", '" + escapeForJs(rpcEndpoint) + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String initializeOfflineAsync(BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.initializeOffline('" + requestId + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String createRandomSeedAsync(int keyType, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.createRandomSeed('" + requestId + "', " + keyType + ")";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String createRandomAsync(int keyType, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.createRandom('" + requestId + "', " + keyType + ")";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String walletFromSeedAsync(int[] seedArray, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        // Seed bytes are staged; JS pulls them.
        JSONObject payload = new JSONObject();
        try {
            payload.put("seedArray", intArrayToJsonArray(seedArray));
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.walletFromSeed('" + requestId + "')");
        return requestId;
    }

    public String walletFromPhraseAsync(String[] words, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        // Seed phrase never enters the script string.
        JSONObject payload = new JSONObject();
        try {
            payload.put("words", stringArrayToJsonArray(words));
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.walletFromPhrase('" + requestId + "')");
        return requestId;
    }

    public String walletFromKeysAsync(String privKeyBase64, String pubKeyBase64,
                                      BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        // Private key never enters the script string.
        JSONObject payload = new JSONObject();
        try {
            payload.put("privKey", privKeyBase64 == null ? "" : privKeyBase64);
            payload.put("pubKey", pubKeyBase64 == null ? "" : pubKeyBase64);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.walletFromKeys('" + requestId + "')");
        return requestId;
    }

    public String sendTransactionAsync(String privKeyBase64, String pubKeyBase64,
                                       String toAddress, String valueWei,
                                       String gasLimit, String rpcEndpoint,
                                       int chainId, boolean advancedSigningEnabled,
                                       BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        // All sensitive fields are pulled, not pushed.
        JSONObject payload = new JSONObject();
        try {
            payload.put("privKey", privKeyBase64 == null ? "" : privKeyBase64);
            payload.put("pubKey", pubKeyBase64 == null ? "" : pubKeyBase64);
            payload.put("to", toAddress == null ? "" : toAddress);
            payload.put("value", valueWei == null ? "" : valueWei);
            payload.put("gasLimit", gasLimit == null ? "" : gasLimit);
            payload.put("rpcEndpoint", rpcEndpoint == null ? "" : rpcEndpoint);
            payload.put("chainId", chainId);
            payload.put("advancedSigning", advancedSigningEnabled);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.sendTransaction('" + requestId + "')");
        return requestId;
    }

    public String sendTokenTransactionAsync(String privKeyBase64, String pubKeyBase64,
                                            String contractAddress, String toAddress,
                                            String amountWei, String gasLimit,
                                            String rpcEndpoint, int chainId,
                                            boolean advancedSigningEnabled,
                                            BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        JSONObject payload = new JSONObject();
        try {
            payload.put("privKey", privKeyBase64 == null ? "" : privKeyBase64);
            payload.put("pubKey", pubKeyBase64 == null ? "" : pubKeyBase64);
            payload.put("contract", contractAddress == null ? "" : contractAddress);
            payload.put("to", toAddress == null ? "" : toAddress);
            payload.put("amount", amountWei == null ? "" : amountWei);
            payload.put("gasLimit", gasLimit == null ? "" : gasLimit);
            payload.put("rpcEndpoint", rpcEndpoint == null ? "" : rpcEndpoint);
            payload.put("chainId", chainId);
            payload.put("advancedSigning", advancedSigningEnabled);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.sendTokenTransaction('" + requestId + "')");
        return requestId;
    }

    public String isValidAddressAsync(String address, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.isValidAddress('" + requestId + "', '"
                + escapeForJs(address) + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    /**
     * SDK-canonical (EIP-55-style) form of {@code address}.
     * Async envelope shape: {@code {success: true, data: {address: "<canonical>"}}}
     * on success, or {@code {success: false, error: "SDK_GETADDRESS_UNAVAILABLE"}}
     * if the bundled SDK build does not export {@code getAddress}.
     * Mirrors iOS {@code JsBridge.shared.getChecksumAddressAsync}.
     */
    public String getChecksumAddressAsync(String address, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.getChecksumAddress('" + requestId + "', '"
                + escapeForJs(address) + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String computeAddressAsync(String pubKeyBase64, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.computeAddress('" + requestId + "', '"
                + escapeForJs(pubKeyBase64) + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String encryptWalletJsonAsync(String walletInputJson, String password,
                                         BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        JSONObject payload = new JSONObject();
        try {
            payload.put("walletInput", walletInputJson == null ? "" : walletInputJson);
            payload.put("password", password == null ? "" : password);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.encryptWalletJson('" + requestId + "')");
        return requestId;
    }

    public String decryptWalletJsonAsync(String walletJson, String password,
                                         BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        JSONObject payload = new JSONObject();
        try {
            payload.put("walletJson", walletJson == null ? "" : walletJson);
            payload.put("password", password == null ? "" : password);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.decryptWalletJson('" + requestId + "')");
        return requestId;
    }

    public String getAllSeedWordsAsync(BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.getAllSeedWords('" + requestId + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String doesSeedWordExistAsync(String word, BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        String jsCall = "bridge.doesSeedWordExist('" + requestId + "', '"
                + escapeForJs(word) + "')";
        evaluateOnMainThread(jsCall);
        return requestId;
    }

    public String scryptDeriveAsync(String password, String saltBase64,
                                    int N, int r, int p, int keyLen,
                                    BridgeCallback callback) {
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        // Password is staged out-of-band.
        JSONObject payload = new JSONObject();
        try {
            payload.put("password", password == null ? "" : password);
            payload.put("salt", saltBase64 == null ? "" : saltBase64);
            payload.put("N", N);
            payload.put("r", r);
            payload.put("p", p);
            payload.put("keyLen", keyLen);
        } catch (JSONException je) {
            throw new RuntimeException(je);
        }
        webViewManager.storePendingPayload(requestId, payload.toString());
        evaluateOnMainThread("bridge.scryptDerive('" + requestId + "')");
        return requestId;
    }

    // ---- DEX (Swap / Liquidity / Pools) methods ----
    //
    // All DEX calls use the pull model exclusively: the entire payload
    // (network config, token addresses, amounts, and for submit calls
    // the base64 key material) is staged via storePendingPayload and
    // the JS handler pulls it by requestId. The JS method name is
    // checked against a fixed allowlist so a corrupted caller cannot
    // turn this into an arbitrary-JS-eval primitive.

    private static final java.util.Set<String> DEX_METHODS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "swapGetTokenMetadata",
                    "swapCheckPairExists",
                    "swapGetAmountsOut",
                    "swapGetAmountsIn",
                    "swapEstimateGas",
                    "swapCheckAllowance",
                    "swapEstimateApproveGas",
                    "swapSubmitApproval",
                    "swapSubmitSwap",
                    "liquidityListPools",
                    "liquidityListPositions",
                    "liquidityGetPairInfo",
                    "liquidityCheckAllowance",
                    "liquiditySubmitApprove",
                    "liquiditySubmitAdd",
                    "liquiditySubmitRemove",
                    "poolsSubmitCreatePair"));

    /**
     * Stage {@code payload} and invoke the allowlisted DEX bridge
     * method. The payload object is caller-built (see the DEX
     * fragments); required common fields are {@code chainId} and
     * {@code rpcEndpoint}, plus optional {@code releaseWq} /
     * {@code releaseFactory} / {@code releaseRouter} overrides for
     * user-defined releases.
     */
    public String dexCallAsync(String method, JSONObject payload, BridgeCallback callback) {
        if (!DEX_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unknown DEX bridge method: " + method);
        }
        String requestId = UUID.randomUUID().toString();
        webViewManager.registerCallback(requestId, callback);
        webViewManager.storePendingPayload(requestId,
                payload == null ? "{}" : payload.toString());
        evaluateOnMainThread("bridge." + method + "('" + requestId + "')");
        return requestId;
    }

    /** Blocking variant of {@link #dexCallAsync}; background threads only. */
    public String dexCall(String method, JSONObject payload) {
        return blockingCall(cb -> dexCallAsync(method, payload, cb));
    }

    // ---- Blocking wrappers ----

    public String initialize(int chainId, String rpcEndpoint) {
        return blockingCall(cb -> initializeAsync(chainId, rpcEndpoint, cb));
    }

    public String initializeOffline() {
        return blockingCall(this::initializeOfflineAsync);
    }

    public String createRandomSeed(int keyType) {
        return blockingCall(cb -> createRandomSeedAsync(keyType, cb));
    }

    public String createRandom(int keyType) {
        return blockingCall(cb -> createRandomAsync(keyType, cb));
    }

    public String walletFromSeed(int[] seedArray) {
        return blockingCall(cb -> walletFromSeedAsync(seedArray, cb));
    }

    public String walletFromPhrase(String[] words) {
        return blockingCall(cb -> walletFromPhraseAsync(words, cb));
    }

    public String walletFromKeys(String privKeyBase64, String pubKeyBase64) {
        return blockingCall(cb -> walletFromKeysAsync(privKeyBase64, pubKeyBase64, cb));
    }

    public String sendTransaction(String privKeyBase64, String pubKeyBase64,
                                  String toAddress, String valueWei,
                                  String gasLimit, String rpcEndpoint, int chainId,
                                  boolean advancedSigningEnabled) {
        return blockingCall(cb -> sendTransactionAsync(
                privKeyBase64, pubKeyBase64, toAddress, valueWei,
                gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, cb));
    }

    public String sendTokenTransaction(String privKeyBase64, String pubKeyBase64,
                                       String contractAddress, String toAddress,
                                       String amountWei, String gasLimit,
                                       String rpcEndpoint, int chainId,
                                       boolean advancedSigningEnabled) {
        return blockingCall(cb -> sendTokenTransactionAsync(
                privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei,
                gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, cb));
    }

    public String isValidAddress(String address) {
        return blockingCall(cb -> isValidAddressAsync(address, cb));
    }

    public String computeAddress(String pubKeyBase64) {
        return blockingCall(cb -> computeAddressAsync(pubKeyBase64, cb));
    }

    public String scryptDerive(String password, String saltBase64,
                               int N, int r, int p, int keyLen) {
        return blockingCall(cb -> scryptDeriveAsync(password, saltBase64, N, r, p, keyLen, cb));
    }

    public String getAllSeedWords() {
        return blockingCall(this::getAllSeedWordsAsync);
    }

    public String doesSeedWordExist(String word) {
        return blockingCall(cb -> doesSeedWordExistAsync(word, cb));
    }

    public String encryptWalletJson(String walletInputJson, String password) {
        return blockingCall(cb -> encryptWalletJsonAsync(walletInputJson, password, cb));
    }

    public String decryptWalletJson(String walletJson, String password) {
        return blockingCall(cb -> decryptWalletJsonAsync(walletJson, password, cb));
    }

    // ---- Internal helpers ----

    private void evaluateOnMainThread(String jsCall) {
        mainHandler.post(() -> webViewManager.evaluateJavascript(jsCall, null));
    }

    private String blockingCall(AsyncInvoker invoker) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException(
                    "Blocking bridge call must not be invoked on the main thread");
        }

        if (!webViewManager.waitUntilReady(DEFAULT_TIMEOUT_SECONDS)) {
            throw new RuntimeException("Bridge WebView did not become ready in time");
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        String requestId = invoker.invoke(new BridgeCallback() {
            @Override
            public void onResult(String jsonResult) {
                resultRef.set(jsonResult);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        boolean timedOut = false;
        try {
            if (!latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                timedOut = true;
                throw new RuntimeException(
                        "Bridge call timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            timedOut = true;
            Thread.currentThread().interrupt();
            throw new RuntimeException("Bridge call interrupted", e);
        } finally {
            // If JavaScript never consumed the staged payload,
            // drop it now rather than waiting for the TTL sweep.
            if (timedOut && requestId != null) {
                webViewManager.removePendingPayload(requestId);
            }
        }

        if (errorRef.get() != null) {
            // Errors also leave nothing staged - getPendingPayload
            // removes on access, but JS may have failed before pulling.
            if (requestId != null) {
                webViewManager.removePendingPayload(requestId);
            }
            throw new RuntimeException("Bridge call failed: " + errorRef.get());
        }
        return resultRef.get();
    }

    /**
     * Escape a string so it can be safely embedded inside a single-quoted
     * JavaScript string literal. Covers backslash, single quote, NUL, CR,
     * LF, and the Unicode line separator characters U+2028 / U+2029 that
     * otherwise terminate a JS string literal on the parser.
     *
     * <p>Note: the preferred path for sensitive values is the pull model
     * above; this helper is only used for non-sensitive push-mode
     * arguments.</p>
     */
    static String escapeForJs(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\"': sb.append("\\\""); break;
                case '\0': sb.append("\\u0000"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static JSONArray intArrayToJsonArray(int[] arr) {
        JSONArray jsonArr = new JSONArray();
        if (arr == null) return jsonArr;
        for (int v : arr) jsonArr.put(v);
        return jsonArr;
    }

    static JSONArray stringArrayToJsonArray(String[] arr) {
        JSONArray jsonArr = new JSONArray();
        if (arr == null) return jsonArr;
        for (String s : arr) jsonArr.put(s == null ? "" : s);
        return jsonArr;
    }

    @FunctionalInterface
    private interface AsyncInvoker {
        /**
         * Returns the generated {@code requestId} so
         * {@link #blockingCall(AsyncInvoker)} can clear any staged
         * payload on the timeout path and not leak sensitive data
         * into the map until TTL expiry.
         */
        String invoke(BridgeCallback callback);
    }
}

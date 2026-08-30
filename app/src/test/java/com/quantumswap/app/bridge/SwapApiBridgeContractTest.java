package com.quantumswap.app.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-shape contract for the Swap Read API integration in the shared
 * JS bridge and the native swap / pools / liquidity / releases screens
 * (port of the web app's swapApi + marketData layers).
 *
 * <p>Contract: every DEX read (route, quote, token facts, pools,
 * positions, pair lookup) goes through the Swap Read API first and falls
 * back to the on-chain RPC path when the API is disabled, unavailable,
 * not serving the active release's factory, or errors. Signing, gas
 * estimation and the transaction builders never consume API data. The
 * API URL and dexId travel per release; the built-in release carries the
 * public defaults. Reserves and amounts from the API are estimates: the
 * chosen path is always re-quoted on-chain through the router before the
 * number reaches the user, and API decimals are never used for math.</p>
 */
public class SwapApiBridgeContractTest {

    private static final String BRIDGE_HTML = "src/main/assets/bridge.html";
    private static final String SWAP_FRAGMENT = "src/main/java/com/quantumswap/app/view/fragment/SwapFragment.java";
    private static final String RELEASES_FRAGMENT = "src/main/java/com/quantumswap/app/view/fragment/ReleasesFragment.java";
    private static final String RELEASE_STORE = "src/main/java/com/quantumswap/app/utils/ReleaseStore.java";
    private static final String DEX_PAYLOADS = "src/main/java/com/quantumswap/app/utils/DexPayloads.java";
    private static final String JS_BRIDGE = "src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java";
    private static final String LANG = "src/main/res/raw/en_us.json";
    private static final String IOS_ROOT = "../../quantumswap-wallet-ios/QuantumSwapWallet/";

    // ---------------------------------------------------------------
    // Bridge: client, config, validators
    // ---------------------------------------------------------------

    @Test
    public void bridgeDefinesSwapApiClient() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        for (String fn : new String[] {"swapApiFetch", "swapApiProbe", "swapApiFirst",
                "swapApiRecordFailure", "swapApiSanitizeUrl", "swapApiIsValidDexId",
                "dexResolveSwapQuote", "dexPickRoute", "dexSimulateExactIn",
                "dexSimulateExactOut", "dexHopReserves", "dexPathFromPayload"}) {
            assertTrue("bridge.html must define " + fn + "(", html.contains("function " + fn + "("));
        }
        for (String c : new String[] {"var SWAP_API_ROUTE_K = 3;", "var SWAP_API_TIMEOUT_MS = 4000;",
                "var SWAP_API_BREAKER_FAILURES = 2;", "var SWAP_API_BREAKER_COOLDOWN_MS = 30000;",
                "var SWAP_API_DEGRADED_LAG_BLOCKS = 20;", "var SWAP_API_ROUTE_MEMO_TTL_MS = 15000;",
                "var SWAP_INSUFFICIENT_LIQUIDITY_ERROR ="}) {
            assertTrue("bridge.html must define the web-app constant: " + c, html.contains(c));
        }
    }

    @Test
    public void urlBuildersMatchContract() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "swapApiPath");
        for (String needle : new String[] {"'/swap/v1/dexes'", "'/status'", "'/token/'", "'/tokens?page='",
                "'/pools?page='", "'&sort='", "'&token='", "'/pool/'", "'/route/'", "'?k='",
                "'/pair/'", "'?account='", "'/positions'", "'/pairs-created?page='"}) {
            assertTrue("swapApiPath must build the contract URL piece " + needle, body.contains(needle));
        }
        assertTrue("k must be clamped to the contract maximum of 5", body.contains("Math.min(5"));
        assertTrue("dexId must be a path parameter on every scoped route",
                body.contains("'/swap/v1/' + ") || body.contains("'/swap/v1/' +"));
    }

    @Test
    public void builtinReleaseCarriesApiUrlAndDexId() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        assertTrue(html.contains("apiUrl: 'https://api.quantumswap.com'"));
        assertTrue(html.contains("dexId: 'quantumswap-beta2'"));
        String body = jsFunctionBody(html, "dexResolveRelease");
        assertTrue("dexResolveRelease must read the per-release API fields from the payload",
                body.contains("payload.releaseApiUrl") && body.contains("payload.releaseDexId"));
        assertTrue("dexResolveRelease must flag custom releases so the API is not assumed",
                body.contains("custom:"));
    }

    @Test
    public void probeRequiresFactoryMatch() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "swapApiProbe");
        assertTrue("the probe must accept the configured dexId only when its factoryAddress equals"
                        + " the active release factory",
                body.contains("factoryAddress") && body.contains(".factory"));
        assertTrue(body.contains("'no-dex'") && body.contains("'ok'") && body.contains("'disabled'"));
        assertTrue("degraded = degraded || lagBlocks > 20", body.contains("SWAP_API_DEGRADED_LAG_BLOCKS"));
    }

    @Test
    public void validatorsEnforceRules() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String facts = jsFunctionBody(html, "swapApiParseTokenFacts");
        assertTrue("identityKnown:false must blank symbol and name", facts.contains("identityKnown"));
        assertTrue("symbol capped at 16, name at 48", facts.contains("16") && facts.contains("48"));
        String route = jsFunctionBody(html, "swapApiParseRoute");
        assertTrue("every hop pair must exist in the pairs map", route.contains("hop pair missing"));
        String page = jsFunctionBody(html, "swapApiRequireDexId");
        assertTrue("dexId echoed in every body must equal the requested one", page.contains("dexId"));
    }

    @Test
    public void amountMathPinsRouterFormula() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String out = jsFunctionBody(html, "dexGetAmountOut");
        assertTrue(out.contains("997n") && out.contains("1000n"));
        String in = jsFunctionBody(html, "dexGetAmountIn");
        assertTrue(in.contains("997n") && in.contains("1000n") && in.contains("+ 1n"));
        assertTrue("exact-out must refuse amountOut >= reserveOut", in.contains("amountOut >= reserveOut"));
    }

    @Test
    public void quoteReQuotesChosenPathOnChain() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "dexResolveSwapQuote");
        for (String needle : new String[] {"router.getAmountsOut(", "router.getAmountsIn(",
                "'api-estimate'", "'router'", "dexPickRoute(", "dexResolveSwapPath("}) {
            assertTrue("dexResolveSwapQuote must pick locally, re-quote the chosen path on-chain and"
                    + " fall back to the RPC search; missing: " + needle, body.contains(needle));
        }
    }

    @Test
    public void readHandlersAreApiFirstWithRpcFallback() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String[][] handlers = {
                {"swapCheckPairExists", "dexFindSwapPath("},
                {"swapGetAmountsOut", "dexResolveSwapQuote("},
                {"swapGetAmountsIn", "dexResolveSwapQuote("},
                {"swapGetTokenMetadata", "token.symbol()"},
                {"liquidityListPools", "dexListFactoryPairAddresses("},
                {"liquidityListPositions", "dexListFactoryPairAddresses("},
                {"liquidityGetPairInfo", "factory.getPair("},
        };
        for (String[] h : handlers) {
            String body = jsHandlerBody(html, h[0]);
            boolean apiFirst = body.contains("swapApiFirst(") || body.contains("dexResolveSwapQuote(");
            assertTrue(h[0] + " must go through the Swap Read API first", apiFirst);
            assertTrue(h[0] + " must keep its on-chain fallback: " + h[1], body.contains(h[1]));
        }
        assertTrue("a swapApiStatus handler must exist for the Releases screen",
                html.contains("swapApiStatus: async function(requestId)"));
        assertTrue("a liquidityListPairsCreated handler must exist for the positions screen",
                html.contains("liquidityListPairsCreated: async function(requestId)"));
        String meta = jsHandlerBody(html, "swapGetTokenMetadata");
        assertTrue("decimals must stay on-chain (API decimals are never used for math)",
                meta.contains("token.decimals()"));
    }

    @Test
    public void submitAndBuildersNeverTouchApi() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        for (String name : new String[] {"dexBuildSwapCall", "dexBuildAddLiquidityCall",
                "dexBuildRemoveLiquidityCall", "dexResolveSwapAmounts", "dexSubmitWrap"}) {
            String body = jsFunctionBody(html, name);
            assertFalse(name + " must never consume Swap Read API data",
                    body.contains("swapApiFetch(") || body.contains("swapApiFirst(")
                            || body.contains("dexResolveSwapQuote("));
        }
        for (String name : new String[] {"swapSubmitApproval", "swapSubmitSwap", "liquiditySubmitApprove",
                "liquiditySubmitAdd", "liquiditySubmitRemove", "poolsSubmitCreatePair",
                "tokensSubmitCreate", "dexEstimateGas"}) {
            String body = jsHandlerBody(html, name);
            assertFalse(name + " must never consume Swap Read API data",
                    body.contains("swapApiFetch(") || body.contains("swapApiFirst(")
                            || body.contains("dexResolveSwapQuote("));
        }
    }

    @Test
    public void builderHonoursPayloadPath() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "dexBuildSwapCall");
        assertTrue("dexBuildSwapCall must use the quoted path from the payload before re-searching",
                body.contains("dexPathFromPayload("));
        String validator = jsFunctionBody(html, "dexPathFromPayload");
        assertTrue("payload path: 2..5 addresses, endpoints equal the mapped from/to, no repeats",
                validator.contains("length < 2") && validator.contains("length > 5"));
    }

    // ---------------------------------------------------------------
    // Native: allowlists, release store, payload, swap fragment
    // ---------------------------------------------------------------

    @Test
    public void allowlistsAddStatusAndPairsCreated() throws Exception {
        String android = read(JS_BRIDGE);
        assertTrue(android.contains("\"swapApiStatus\"") && android.contains("\"liquidityListPairsCreated\""));
        File ios = locate(IOS_ROOT + "JsBridge/JsBridge.swift", false);
        Assume.assumeTrue("iOS repo not checked out beside this one", ios != null);
        String swift = new String(Files.readAllBytes(ios.toPath()), StandardCharsets.UTF_8);
        assertTrue("iOS dexMethods must mirror the Android allowlist",
                swift.contains("\"swapApiStatus\"") && swift.contains("\"liquidityListPairsCreated\""));
    }

    @Test
    public void releaseStoreCarriesApiFields() throws Exception {
        Class<?> cls = Class.forName("com.quantumswap.app.utils.ReleaseStore");
        Object builtin = cls.getField("BUILTIN").get(null);
        Class<?> rel = builtin.getClass();
        assertEquals("https://api.quantumswap.com", rel.getField("apiUrl").get(builtin));
        assertEquals("quantumswap-beta2", rel.getField("dexId").get(builtin));
        assertEquals("dexCustomReleases2", cls.getField("ITEM_RELEASES").get(null));
        assertEquals("dexActiveRelease2", cls.getField("ITEM_ACTIVE").get(null));
        String src = stripJava(read(RELEASE_STORE));
        String apply = javaMethodBody(src, "public static void applyActiveRelease(");
        assertTrue("applyActiveRelease must send releaseApiUrl / releaseDexId for every release",
                apply.contains("\"releaseApiUrl\"") && apply.contains("\"releaseDexId\""));
        assertFalse("no unsuffixed v1 key literal may remain",
                src.contains("\"dexCustomReleases\"") || src.contains("\"dexActiveRelease\""));
        assertTrue("DexPayloads.base must keep routing through applyActiveRelease",
                read(DEX_PAYLOADS).contains("ReleaseStore.applyActiveRelease("));
    }

    @Test
    public void swapApiConfigValidates() throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("com.quantumswap.app.utils.SwapApiConfig");
        } catch (ClassNotFoundException e) {
            fail("SwapApiConfig must exist (pure URL / dexId sanitiser shared by the screens)");
            return;
        }
        java.lang.reflect.Method url = cls.getMethod("sanitizeUrl", String.class);
        java.lang.reflect.Method dex = cls.getMethod("isValidDexId", String.class);
        assertEquals("https://api.quantumswap.com", url.invoke(null, "https://api.quantumswap.com/"));
        assertEquals("http://127.0.0.1:8182", url.invoke(null, "http://127.0.0.1:8182"));
        for (String bad : new String[] {"ftp://x", "https://u:p@x", "https://x/?a=1", "https://x/#f",
                "javascript:alert(1)", "", null, "https://" + "a".repeat(200)}) {
            assertEquals("must reject " + bad, "", url.invoke(null, bad));
        }
        assertEquals(Boolean.TRUE, dex.invoke(null, "quantumswap-beta2"));
        assertEquals(Boolean.FALSE, dex.invoke(null, "bad id!"));
        assertEquals(Boolean.FALSE, dex.invoke(null, "a".repeat(65)));
        assertEquals(Boolean.FALSE, dex.invoke(null, ""));
        assertEquals(Boolean.FALSE, dex.invoke(null, (String) null));
    }

    @Test
    public void swapFragmentPassesPath() throws Exception {
        String src = stripJava(read(SWAP_FRAGMENT));
        String args = javaMethodBody(src, "private void putSwapArgs(");
        assertTrue("putSwapArgs must send the quoted path so the builder does not re-search",
                args.contains("\"path\""));
        assertTrue(src.contains("lastQuotedPath"));
        assertTrue("the review must show the quote source when it is an indexed estimate",
                src.contains("swap-quote-source-indexed"));
    }

    @Test
    public void releasesScreenShowsApiFields() throws Exception {
        String src = stripJava(read(RELEASES_FRAGMENT));
        for (String needle : new String[] {"editText_releases_api_url", "editText_releases_dex_id",
                "swapApiStatus", "swap-api-status-indexed", "swap-api-status-off",
                "swap-api-status-not-served", "swap-api-status-unavailable",
                "release-invalid-api-url", "release-invalid-dex-id"}) {
            assertTrue("ReleasesFragment missing: " + needle, src.contains(needle));
        }
    }

    @Test
    public void strongboxUntouched() throws Exception {
        for (String p : new String[] {"src/main/java/com/quantumswap/app/strongbox/StrongboxPayload.java",
                "src/main/java/com/quantumswap/app/keystorage/UnlockCoordinator.java",
                "src/main/java/com/quantumswap/app/keystorage/SecureStorage.java"}) {
            String s = read(p);
            assertFalse(p + " must not know about releases or the Swap Read API",
                    s.contains("dexCustomReleases") || s.contains("apiUrl") || s.contains("dexId"));
        }
        String store = stripJava(read(RELEASE_STORE));
        assertFalse("ReleaseStore reaches the strongbox only through secureItems",
                store.contains("payload.wallets") || store.contains("passwordWrap"));
        assertTrue(store.contains("secureItems"));
    }

    @Test
    public void langKeysPresentAndInParityWithIos() throws Exception {
        String lang = read(LANG);
        String[][] keys = {
                {"swap-insufficient-liquidity", "Not enough liquidity on this route for the requested amount."},
                {"swap-quote-source-indexed", "Estimated from indexed reserves · block [BLOCK]"},
                {"pools-indexed-at", "Indexed at block [BLOCK]"},
                {"pools-sort-liquidity", "Sort: liquidity"},
                {"pools-sort-newest", "Sort: newest"},
                {"pools-load-all", "Load all pairs"},
                {"pools-page-of", "Page [PAGE] of [COUNT] · [TOTAL] pools"},
                {"pools-empty-api", "No pools found yet. Try loading all pairs, or create one."},
                {"positions-pools-created", "Pools you created"},
                {"positions-empty-api", "No liquidity positions found for this account on the active release."},
                {"positions-capped", "Showing the first 1000 positions tracked for this account."},
                {"release-api-url", "Swap Read API URL"},
                {"release-dex-id", "Swap Read API dexId"},
                {"release-api-off", "Off (using RPC)"},
                {"release-invalid-api-url", "Enter a valid http(s) URL for the Swap Read API (no credentials, query or fragment; max 200 characters)."},
                {"release-invalid-dex-id", "Swap Read API dexId may only contain letters, digits, - and _ (max 64)."},
                {"swap-api-status-indexed", "Swap Read API: indexed [PAIRS] pools · [TOKENS] tokens · block [BLOCK]"},
                {"swap-api-status-behind", "([LAG] behind)"},
                {"swap-api-status-off", "Swap Read API: off for this release (using RPC)."},
                {"swap-api-status-not-served", "Swap Read API: this dexId is not served for this factory (using RPC)."},
                {"swap-api-status-unavailable", "Swap Read API unavailable (using RPC)."},
                {"token-fee-on-transfer", "fee on transfer"},
                {"swap-api-fallback-toast", "Swap Read API unavailable ([DETAIL]); using RPC."}};
        for (String[] kv : keys) {
            assertEquals("en_us.json must define \"" + kv[0] + "\"", kv[1], langValue(lang, kv[0]));
        }
        File ios = locate(IOS_ROOT + "Resources/en_us.json", false);
        Assume.assumeTrue("iOS repo not checked out beside this one", ios != null);
        String iosLang = new String(Files.readAllBytes(ios.toPath()), StandardCharsets.UTF_8);
        for (String[] kv : keys) {
            assertEquals("iOS en_us.json must define \"" + kv[0] + "\" identically", kv[1], langValue(iosLang, kv[0]));
        }
    }

    @Test
    public void bridgeHtmlByteIdenticalWithIos() throws Exception {
        File ios = locate(IOS_ROOT + "Resources/bridge.html", false);
        Assume.assumeTrue("iOS repo not checked out beside this one", ios != null);
        byte[] a = Files.readAllBytes(locate(BRIDGE_HTML, true).toPath());
        byte[] b = Files.readAllBytes(ios.toPath());
        assertTrue("bridge.html must be byte-identical between Android and iOS", java.util.Arrays.equals(a, b));
    }

    @Test
    public void noMainnetHashesInNewSources() throws Exception {
        // 64-hex literals are allowed only for the registry / release constants.
        Pattern hex = Pattern.compile("0x[0-9a-fA-F]{64}");
        for (String p : new String[] {SWAP_FRAGMENT, RELEASES_FRAGMENT, DEX_PAYLOADS,
                "src/main/java/com/quantumswap/app/utils/SwapApiConfig.java",
                "src/test/java/com/quantumswap/app/bridge/SwapApiBridgeContractTest.java"}) {
            File f = locate(p, false);
            if (f == null) continue;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = hex.matcher(s);
            assertFalse(p + " must not embed a 64-hex literal (tx hash / address)", m.find());
        }
    }

    @Test
    public void fallbackIsReportedAndToasted() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        assertTrue("swapApiFirst must record the failed request (kind, HTTP status, server message)",
                jsFunctionBody(html, "swapApiFirst").contains("swapApiNoteFallback("));
        assertTrue("a failed probe must be reported too",
                jsFunctionBody(html, "swapApiProbe").contains("swapApiNoteFallback("));
        assertTrue(html.contains("function swapApiWithFallback(") && html.contains("function swapApiSendResult("));
        for (String h : new String[] {"swapCheckPairExists", "swapGetAmountsOut", "swapGetAmountsIn",
                "swapGetTokenMetadata", "liquidityListPools", "liquidityListPositions", "liquidityGetPairInfo"}) {
            String body = jsHandlerBody(html, h);
            assertTrue(h + " must attach apiFallback to its result", body.contains("swapApiSendResult("));
            assertFalse(h + " must not bypass the fallback report", body.contains(" sendResult("));
        }
        String note = jsFunctionBody(html, "swapApiNoteFallback");
        assertTrue("the detail must include the HTTP status and the server message",
                note.contains("'HTTP '") && note.contains("message"));
        assertTrue("SwapApiToast must exist", locate("src/main/java/com/quantumswap/app/utils/SwapApiToast.java", false) != null);
        String toast = stripJava(read("src/main/java/com/quantumswap/app/utils/SwapApiToast.java"));
        assertTrue("the toast must stay up for LENGTH_LONG (~3.5 s)", toast.contains("Toast.LENGTH_LONG"));
        assertFalse("the legacy ShowToast helper cancels the toast at 600 ms", toast.contains("ShowToast("));
        for (String p : new String[] {SWAP_FRAGMENT,
                "src/main/java/com/quantumswap/app/view/fragment/PoolsFragment.java",
                "src/main/java/com/quantumswap/app/view/fragment/LiquidityFragment.java"}) {
            assertTrue(p + " must toast the API fallback before rendering the RPC result",
                    stripJava(read(p)).contains("SwapApiToast.showIfFallback("));
        }
        File ios = locate(IOS_ROOT + "Utils/SwapApiToast.swift", false);
        Assume.assumeTrue("iOS repo not checked out beside this one", ios != null);
    }

    // ---------------------------------------------------------------
    // helpers (same conventions as SwapNativeBridgeContractTest)
    // ---------------------------------------------------------------

    private static File locate(String path, boolean required) {
        File f = new File(path);
        if (!f.exists()) f = new File("app/" + path);
        if (!f.exists() && required) {
            Assume.assumeTrue("could not locate " + path + " from "
                    + new File(".").getAbsolutePath(), false);
        }
        return f.exists() ? f : null;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(locate(path, true).toPath()), StandardCharsets.UTF_8);
    }

    private static String stripJava(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)//[^\n]*", "");
    }

    private static String stripJs(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)^\\s*//[^\n]*", "");
    }

    private static String langValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String jsHandlerBody(String src, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*:\\s*(async\\s+)?function\\s*\\(").matcher(src);
        assertTrue("bridge handler not found: " + name, m.find());
        return braceBlock(src, m.start());
    }

    private static String jsFunctionBody(String src, String name) {
        Matcher m = Pattern.compile("(async\\s+)?function\\s+" + Pattern.quote(name) + "\\s*\\(").matcher(src);
        assertTrue("bridge function not found: " + name, m.find());
        return braceBlock(src, m.start());
    }

    private static String javaMethodBody(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("method not found: " + signature, i >= 0);
        return braceBlock(src, i);
    }

    private static String braceBlock(String src, int from) {
        int open = src.indexOf('{', from);
        assertTrue("no opening brace after offset " + from, open >= 0);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        fail("unbalanced braces after offset " + from);
        return null;
    }
}

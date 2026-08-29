package com.quantumswap.app.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.quantumswap.app.gas.GasKind;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native-coin swap contract: swapping FROM native Q, TO native Q, and
 * Q &lt;-&gt; WQ (wrap / unwrap).
 *
 * <p>QuantumSwap is a Uniswap-v2 DEX; the router trades the wrapped coin
 * WQ. The UI lists native Q as the sentinel {@code "Q"}, which the bridge
 * maps to the release's WQ address for routing. The regression class this
 * suite pins: the bridge's only swap submit path called
 * {@code swapExactTokensForTokens} unconditionally with no {@code value},
 * so a Q -&gt; token swap planned {@code [Approve WQ] -&gt; [Swap]} -- the
 * approval succeeds (approving with a zero balance is legal), then the
 * router's {@code transferFrom} of WQ reverts because the user holds native
 * Q, not WQ. token -&gt; Q silently paid out WQ. Q &lt;-&gt; WQ mapped both sides
 * to the same address and either found no route or bounced through an
 * unrelated pair.
 *
 * <p>The contract (mirrors the QuantumSwap web app and the in-repo liquidity
 * path, which already uses {@code addLiquidityETH} with {@code value}):
 * <ul>
 *   <li>a single {@code dexBuildSwapCall} chooses the router method --
 *   {@code swapExactETHForTokens} (value = amountIn) for a native from-side,
 *   {@code swapExactTokensForETH} for a native to-side, else
 *   {@code swapExactTokensForTokens} -- and BOTH submit and gas-estimate go
 *   through it, so they cannot diverge;</li>
 *   <li>Q &lt;-&gt; WQ is a Wrap / Unwrap: one transaction to the WQ contract
 *   ({@code deposit} with value / {@code withdraw(amount)}), no router, no
 *   approval, and the route finder rejects equal mapped addresses;</li>
 *   <li>the client relabels the action button, skips the approve step for
 *   a native from-side, and has wrap / unwrap gas kinds with matching
 *   {@code dexEstimateGas} branches;</li>
 *   <li>the shared bridge and the four new lang keys stay byte-identical
 *   with the iOS repo (checked when it is present next to this one).</li>
 * </ul>
 *
 * <p>Grep-style source lint, in the shape of {@code SendBridgeContractTest}:
 * the bridge cannot be instantiated on the JVM (it needs a Looper), so the
 * files are read as text and asserted on with comments stripped.
 */
public class SwapNativeBridgeContractTest {

    private static final String BRIDGE_HTML = "src/main/assets/bridge.html";
    private static final String JS_BRIDGE = "src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java";
    private static final String SWAP_FRAGMENT = "src/main/java/com/quantumswap/app/view/fragment/SwapFragment.java";
    private static final String LANG = "src/main/res/raw/en_us.json";
    private static final String IOS_LANG = "../../quantumswap-wallet-ios/QuantumSwapWallet/Resources/en_us.json";
    private static final String IOS_BRIDGE = "../../quantumswap-wallet-ios/QuantumSwapWallet/Resources/bridge.html";

    // ---------------------------------------------------------------
    // bridge.html: one builder for the router call
    // ---------------------------------------------------------------

    @Test
    public void bridgeDefinesSwapCallBuilder() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        assertTrue("bridge.html must define dexBuildSwapCall(payload, release, provider):"
                        + " the single place that picks the router method (and value) for a"
                        + " swap, mirroring dexBuildAddLiquidityCall.",
                html.contains("async function dexBuildSwapCall("));
    }

    @Test
    public void swapSubmitUsesBuilderNotHardcodedTokenSwap() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsHandlerBody(html, "swapSubmitSwap");
        assertTrue("swapSubmitSwap must obtain its router call from dexBuildSwapCall.",
                body.contains("dexBuildSwapCall("));
        assertFalse("swapSubmitSwap must not hard-code swapExactTokensForTokens: for a"
                        + " native from-side that call carries no value and the router's"
                        + " transferFrom of WQ reverts.",
                body.contains("swapExactTokensForTokens"));
    }

    @Test
    public void gasEstimateSwapUsesBuilder() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsHandlerBody(html, "dexEstimateGas");
        assertTrue("dexEstimateGas kind 'swap' must build the call via dexBuildSwapCall"
                        + " so the estimate matches what will be submitted.",
                body.contains("dexBuildSwapCall("));
        assertFalse("dexEstimateGas must not hard-code populateTransaction"
                        + ".swapExactTokensForTokens.",
                body.contains("populateTransaction.swapExactTokensForTokens"));
    }

    @Test
    public void noDirectTokenSwapInvocationAnywhere() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        assertFalse("No code path in bridge.html may invoke .swapExactTokensForTokens("
                        + " directly; the method name may only appear as a string chosen"
                        + " by dexBuildSwapCall. (Also catches the dead swapEstimateGas"
                        + " handler, which must be removed.)",
                html.contains(".swapExactTokensForTokens("));
    }

    @Test
    public void builderEmitsNativeRouterVariants() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "dexBuildSwapCall");
        // The ...SupportingFeeOnTransferTokens variants derive each hop's
        // input from the pair's actual balance delta, so tokens that burn
        // or tax on transfer (which the standard variants reject with the
        // pair's K check) swap correctly; they are equally correct for
        // normal tokens. Exact-in only, which is all the clients send.
        assertTrue("dexBuildSwapCall must use swapExactETHForTokensSupportingFeeOnTransferTokens"
                        + " for a native from-side.",
                body.contains("'swapExactETHForTokensSupportingFeeOnTransferTokens'"));
        assertTrue("dexBuildSwapCall must use swapExactTokensForETHSupportingFeeOnTransferTokens"
                        + " for a native to-side so the user receives Q, not WQ, and a"
                        + " burn/tax-on-transfer input token does not fail the pair's K check.",
                body.contains("'swapExactTokensForETHSupportingFeeOnTransferTokens'"));
        assertTrue("dexBuildSwapCall must use swapExactTokensForTokensSupportingFeeOnTransferTokens"
                        + " for token -> token.",
                body.contains("'swapExactTokensForTokensSupportingFeeOnTransferTokens'"));
        for (String bare : new String[] {"'swapExactETHForTokens'", "'swapExactTokensForETH'",
                "'swapExactTokensForTokens'"}) {
            assertFalse("dexBuildSwapCall must not emit the standard (non fee-on-transfer)"
                            + " router method " + bare + ": a burn/tax-on-transfer input"
                            + " token reverts it with the pair's K check.",
                    body.contains(bare));
        }
        assertTrue("dexBuildSwapCall must attach the input amount as the native value"
                        + " for a native from-side.",
                body.contains("value: amounts.amountInWei"));
    }

    // ---------------------------------------------------------------
    // bridge.html: wrap / unwrap, allowance short-circuit, route guard
    // ---------------------------------------------------------------

    @Test
    public void bridgeHasWrapAndUnwrapHandlers() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        for (String needle : new String[] {"swapSubmitWrap:", "swapSubmitUnwrap:",
                "function dexBuildWrapCall(", "QuantumSwapSDK.WQ.connect(",
                "'deposit'", "'withdraw'"}) {
            assertTrue("bridge.html must implement Q <-> WQ as WQ.deposit / WQ.withdraw"
                            + " against the WQ contract (no router); missing: " + needle,
                    html.contains(needle));
        }
    }

    @Test
    public void allowanceCheckShortCircuitsNative() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsHandlerBody(html, "swapCheckAllowance");
        assertTrue("swapCheckAllowance must report a native from-side as needing no"
                        + " allowance; otherwise a stale client plans an Approve WQ step"
                        + " for coins the user holds unwrapped.",
                body.contains("payload.fromTokenValue === 'Q'"));
    }

    @Test
    public void routeFinderRejectsEqualMappedAddresses() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsFunctionBody(html, "dexFindSwapPath");
        assertTrue("dexFindSwapPath must return null when both sides map to the same"
                        + " address (Q vs WQ); otherwise it searches getPair(WQ, WQ) or"
                        + " bounces through an unrelated pair.",
                Pattern.compile("if\\s*\\(\\s*fromAddr\\.toLowerCase\\(\\)\\s*===\\s*toAddr\\.toLowerCase\\(\\)\\s*\\)\\s*return null;")
                        .matcher(body).find());
    }

    // ---------------------------------------------------------------
    // Java side: allowlist, gas kinds, fragment
    // ---------------------------------------------------------------

    @Test
    public void javaAllowlistContainsWrapUnwrap() throws Exception {
        String src = stripJava(read(JS_BRIDGE));
        assertTrue("QuantumSwapJSBridge.DEX_METHODS must allowlist swapSubmitWrap.",
                src.contains("\"swapSubmitWrap\""));
        assertTrue("QuantumSwapJSBridge.DEX_METHODS must allowlist swapSubmitUnwrap.",
                src.contains("\"swapSubmitUnwrap\""));
    }

    @Test
    public void gasKindHasWrapAndUnwrap() {
        GasKind wrap;
        GasKind unwrap;
        try {
            wrap = GasKind.valueOf("WRAP");
            unwrap = GasKind.valueOf("UNWRAP");
        } catch (IllegalArgumentException e) {
            fail("GasKind must define WRAP and UNWRAP so the wrap / unwrap steps can be"
                    + " gas-estimated through the shared TxStepsDialog.");
            return;
        }
        assertEquals("wrap", wrap.txKind);
        assertEquals("unwrap", unwrap.txKind);
    }

    @Test
    public void everyGasKindHasAnEstimateBranch() throws Exception {
        String html = stripJs(read(BRIDGE_HTML));
        String body = jsHandlerBody(html, "dexEstimateGas");
        for (GasKind k : GasKind.values()) {
            assertTrue("dexEstimateGas has no branch for txKind '" + k.txKind + "'"
                            + " (GasKind." + k.name() + "); the bridge would answer"
                            + " 'Unknown txKind' and the estimate silently falls back.",
                    body.contains("kind === '" + k.txKind + "'"));
        }
    }

    @Test
    public void swapFragmentWrapModeRelabelsAndSkipsApproveForNative() throws Exception {
        String src = stripJava(read(SWAP_FRAGMENT));
        for (String needle : new String[] {"private String wrapMode()", "GasKind.WRAP",
                "GasKind.UNWRAP", "\"swapSubmitWrap\"", "\"swapSubmitUnwrap\"",
                "nextButton.setText(modeLabel("}) {
            assertTrue("SwapFragment must implement wrap / unwrap mode (button relabel,"
                            + " wrap/unwrap gas kinds and submit methods); missing: " + needle,
                    src.contains(needle));
        }
        String next = javaMethodBody(src, "private void onNextClick(");
        assertTrue("onNextClick must skip the allowance check / approve step and open"
                        + " the steps dialog directly (showStepsDialog(false)) for wrap,"
                        + " unwrap and a native from-side.",
                next.contains("showStepsDialog(false)"));
    }

    // ---------------------------------------------------------------
    // Localization + cross-repo parity
    // ---------------------------------------------------------------

    @Test
    public void langKeysPresentAndInParityWithIos() throws Exception {
        String lang = read(LANG);
        String[][] keys = {{"wrap", "Wrap"}, {"unwrap", "Unwrap"},
                {"step-wrap", "Wrap"}, {"step-unwrap", "Unwrap"}};
        for (String[] kv : keys) {
            assertEquals("en_us.json must define \"" + kv[0] + "\"", kv[1], langValue(lang, kv[0]));
        }
        File ios = locate(IOS_LANG, false);
        if (ios != null) {
            String iosLang = new String(Files.readAllBytes(ios.toPath()), StandardCharsets.UTF_8);
            for (String[] kv : keys) {
                assertEquals("iOS en_us.json must carry the same value for \"" + kv[0]
                        + "\" (JsonInteractParityTest keeps the key sets in lockstep).",
                        kv[1], langValue(iosLang, kv[0]));
            }
        }
    }

    @Test
    public void bridgeHtmlByteIdenticalWithIos() throws Exception {
        File ios = locate(IOS_BRIDGE, false);
        Assume.assumeTrue("iOS repo not present next to this one; parity check skipped",
                ios != null);
        assertArrayEquals("bridge.html must stay byte-identical between the Android and"
                        + " iOS repos (shared DEX bridge).",
                Files.readAllBytes(locate(BRIDGE_HTML, true).toPath()),
                Files.readAllBytes(ios.toPath()));
    }

    // ---------------------------------------------------------------
    // helpers
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

    /** Strip JS block and line comments. Line comments are stripped only
     *  when they start a statement (after whitespace or ';'), so URLs and
     *  string literals containing "//" survive. */
    private static String stripJs(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)^\\s*//[^\n]*", "");
    }

    private static String langValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Body of `name: async function(requestId) {...}` or `name: function(...) {...}`. */
    private static String jsHandlerBody(String src, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "\\s*:\\s*(async\\s+)?function\\s*\\(").matcher(src);
        assertTrue("bridge handler not found: " + name, m.find());
        return braceBlock(src, m.start());
    }

    /** Body of `[async] function name(...) {...}`. */
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

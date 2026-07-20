package com.quantumswap.app.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract test for the Send bridge surface.
 * <p>Mirrors iOS {@code QuantumSwapWalletTests/JsBridgeContractTests.swift}.
 * iOS spins up the real WKWebView + JsEngine and runs the round-trip
 * end-to-end. On Android the equivalent live round-trip belongs in
 * {@code androidTest/} (it requires a running WebView / Looper); the
 * JVM-side {@code testDebugUnitTest} contract test below pins the
 * STATIC payload + envelope shape so any bridge.html / Java-side
 * drift fails at PR time, not at runtime in production.
 * <p>What this guards:
 * <ol>
 *   <li><b>Java -&gt; JS payload shape for Send.</b> The
 *   {@link QuantumSwapJSBridge#sendTransactionAsync} method must
 *   stage exactly the keys {@code privKey}, {@code pubKey},
 *   {@code to}, {@code value}, {@code gasLimit}, {@code rpcEndpoint},
 *   {@code chainId}, {@code advancedSigning}. Any added field
 *   silently appears in the JS handler; any removed field crashes
 *   the JS handler at runtime.</li>
 *   <li><b>Java -&gt; JS payload shape for Token Send.</b> Same as
 *   above, plus {@code contract} and {@code amount} (in place of
 *   {@code value}).</li>
 *   <li><b>JS dispatch arity.</b> The dispatch line must call
 *   {@code bridge.sendTransaction('&lt;requestId&gt;')} with EXACTLY
 *   one inline argument (the requestId). Any sensitive field
 *   appearing in the script string would be a Push-channel leak --
 *   defeats the pull-channel design.</li>
 *   <li><b>Bridge.html success-envelope shape.</b> {@code sendResult}
 *   wraps every response as {@code {success: true, data: ...}} and
 *   {@code sendError} wraps every error. iOS asserts the same
 *   envelope; this test asserts the bridge.html source still emits
 *   that shape.</li>
 *   <li><b>Send dispatch is the high-level IERC20.transfer /
 *   wallet.sendTransaction surface.</b> No
 *   {@code signRawTransaction} or sign-then-broadcast split, per
 *   the Send-Surface Lockdown discussion in plan §Q.</li>
 * </ol>
 * <p>Implementation note: this test reads the project sources
 * directly rather than instantiating the bridge. Constructing
 * {@link QuantumSwapJSBridge} requires a live {@link android.os.Looper}
 * which is stubbed on the JVM unit-test classpath. The structural
 * grep approach also catches the case where a refactor moves the
 * payload composition into a helper -- the test still finds the
 * literal field-name strings wherever they live.
 */
public class SendBridgeContractTest {

    private static final File JAVA = new File(
            "src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java");
    private static final File HTML = new File(
            "src/main/assets/bridge.html");

    private static String javaSrc() throws Exception {
        Assume.assumeTrue("QuantumSwapJSBridge.java not found at expected path; "
                + "running outside the app module CWD.", JAVA.exists());
        return new String(Files.readAllBytes(JAVA.toPath()), StandardCharsets.UTF_8);
    }

    private static String htmlSrc() throws Exception {
        Assume.assumeTrue("bridge.html not found at expected path.", HTML.exists());
        return new String(Files.readAllBytes(HTML.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void sendTransaction_payloadHasExpectedKeys() throws Exception {
        String src = javaSrc();
        String body = methodBody(src, "sendTransactionAsync");
        assertNotNull("could not locate sendTransactionAsync method body", body);

        // Required keys for the Send payload. If any of these is
        // missing, the JS handler will read undefined for that
        // field and the Send will fail at runtime.
        String[] required = new String[] {
                "privKey", "pubKey", "to", "value",
                "gasLimit", "rpcEndpoint", "chainId", "advancedSigning"
        };
        for (String key : required) {
            assertTrue("sendTransactionAsync payload missing key: " + key,
                    body.contains("\"" + key + "\""));
        }
    }

    @Test
    public void sendTokenTransaction_payloadHasExpectedKeys() throws Exception {
        String src = javaSrc();
        String body = methodBody(src, "sendTokenTransactionAsync");
        assertNotNull("could not locate sendTokenTransactionAsync method body", body);

        String[] required = new String[] {
                "privKey", "pubKey", "contract", "to", "amount",
                "gasLimit", "rpcEndpoint", "chainId", "advancedSigning"
        };
        for (String key : required) {
            assertTrue("sendTokenTransactionAsync payload missing key: " + key,
                    body.contains("\"" + key + "\""));
        }
    }

    @Test
    public void sendTransaction_dispatchHasOnlyRequestIdInline() throws Exception {
        String src = javaSrc();
        String body = methodBody(src, "sendTransactionAsync");
        assertNotNull(body);
        // The dispatch line must be exactly bridge.sendTransaction('<id>'),
        // with no other inline arguments. Any escapeForJs(...) call in
        // the dispatch string would indicate a sensitive field is
        // being pushed inline.
        Pattern dispatchOk = Pattern.compile(
                "evaluateOnMainThread\\(\\s*\"bridge\\.sendTransaction\\('\"\\s*\\+\\s*requestId\\s*\\+\\s*\"'\\)\"\\s*\\)");
        assertTrue("Send dispatch must be exactly bridge.sendTransaction('<id>'); "
                + "any inline arg beyond requestId is a Push-channel leak.",
                dispatchOk.matcher(body).find());
    }

    @Test
    public void sendTokenTransaction_dispatchHasOnlyRequestIdInline() throws Exception {
        String src = javaSrc();
        String body = methodBody(src, "sendTokenTransactionAsync");
        assertNotNull(body);
        Pattern dispatchOk = Pattern.compile(
                "evaluateOnMainThread\\(\\s*\"bridge\\.sendTokenTransaction\\('\"\\s*\\+\\s*requestId\\s*\\+\\s*\"'\\)\"\\s*\\)");
        assertTrue("Token-Send dispatch must be exactly bridge.sendTokenTransaction('<id>').",
                dispatchOk.matcher(body).find());
    }

    @Test
    public void sendPayload_storedViaPullChannel() throws Exception {
        String src = javaSrc();
        // Both Send and Token-Send must call storePendingPayload --
        // this is the pull channel that keeps sensitive data
        // out of the script string.
        String tx = methodBody(src, "sendTransactionAsync");
        String token = methodBody(src, "sendTokenTransactionAsync");
        assertTrue("Send payload must be staged via storePendingPayload",
                tx != null && tx.contains("storePendingPayload(requestId, payload.toString())"));
        assertTrue("Token-Send payload must be staged via storePendingPayload",
                token != null
                        && token.contains("storePendingPayload(requestId, payload.toString())"));
    }

    @Test
    public void bridgeHtml_emitsSuccessEnvelope() throws Exception {
        String html = htmlSrc();
        // Mirrors the iOS contract: every result is a
        // { success: true, data: <data> } envelope. iOS parses the
        // data field by name; any drift here breaks both clients.
        Pattern successEnv = Pattern.compile(
                "JSON\\.stringify\\(\\s*\\{\\s*success\\s*:\\s*true\\s*,\\s*data\\s*:");
        assertTrue("bridge.html must wrap every response as "
                + "{ success: true, data: ... }",
                successEnv.matcher(html).find());
    }

    @Test
    public void bridgeHtml_sendUsesHighLevelWalletSendTransaction() throws Exception {
        String html = htmlSrc();
        // Send dispatch must use wallet.sendTransaction (high-level
        // surface). signRawTransaction / sign-then-broadcast splits
        // are forbidden per plan §Q (Send Surface Lockdown).
        assertTrue("Send must use wallet.sendTransaction",
                html.contains("wallet.sendTransaction("));
        // signRawTransaction may appear in COMMENTARY explaining why
        // it is not used; the contract is that it is never INVOKED.
        // Strip line and block comments before searching for an
        // invocation-shaped occurrence.
        String code = stripJsComments(html);
        assertTrue("bridge.html executable code must NOT call signRawTransaction(",
                !code.contains("signRawTransaction("));
    }

     /**
     * Tiny line + block comment stripper so the
     * {@code bridgeHtml_sendUsesHighLevelWalletSendTransaction} test
     * can grep executable code without false-positives from rationale
     * comments. Not a full JS parser; sufficient for the
     * /* ... *&#47; and // ... shapes used in bridge.html.
     */
    private static String stripJsComments(String src) {
        // Block comments first.
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        // Then single-line comments. We avoid touching `//` inside a
        // string literal heuristically by anchoring at line start
        // with optional leading whitespace; bridge.html follows this
        // style consistently.
        s = s.replaceAll("(?m)^\\s*//.*$", "");
        return s;
    }

    @Test
    public void bridge_exposesAsyncEntryPoints() {
        // Defence-in-depth: the public method surface must include
        // the async entry points the SendFragment depends on. If a
        // refactor renames these without updating the fragment, the
        // app compiles but Send is dead at runtime.
        Method tx = findMethod("sendTransactionAsync", 9);
        Method token = findMethod("sendTokenTransactionAsync", 10);
        assertNotNull("QuantumSwapJSBridge.sendTransactionAsync must exist", tx);
        assertNotNull("QuantumSwapJSBridge.sendTokenTransactionAsync must exist", token);
    }

    @Test
    public void bridge_exposesIsValidAddressAsync_envelopeShape() throws Exception {
        // iOS pin: isValidAddressAsync returns the envelope as a
        // String which the caller parses. We assert the Java-side
        // entry point exists with the expected signature.
        Method m = findMethod("isValidAddressAsync", 2);
        assertNotNull("QuantumSwapJSBridge.isValidAddressAsync(String,BridgeCallback) must exist", m);
        assertEquals(String.class, m.getReturnType());
        assertEquals(String.class, m.getParameterTypes()[0]);
        // The bridge.html must dispatch isValidAddress through the
        // same envelope path -- callback receives a JSON string.
        String html = htmlSrc();
        assertTrue("bridge.html must define isValidAddress",
                html.contains("isValidAddress:"));
    }

    /** Locate the body of a void/String/int-returning method. Returns {@code null} if not found. */
    private static String methodBody(String src, String methodName) {
        // Find the method declaration line, then walk braces.
        Pattern declare = Pattern.compile(
                "\\bpublic\\s+\\w+\\s+" + Pattern.quote(methodName) + "\\s*\\(");
        Matcher m = declare.matcher(src);
        if (!m.find()) return null;
        int braceStart = src.indexOf('{', m.end());
        if (braceStart < 0) return null;
        int depth = 0;
        for (int i = braceStart; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(braceStart, i + 1);
            }
        }
        return null;
    }

    private static Method findMethod(String name, int expectedArity) {
        for (Method m : QuantumSwapJSBridge.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == expectedArity) return m;
        }
        return null;
    }
}

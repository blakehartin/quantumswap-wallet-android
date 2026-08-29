package com.quantumswap.app.gas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Gas-estimate failures in the transaction-steps dialog must be shown to
 * the user, with the error the node returned, and the user must be able
 * to set the gas limit manually.
 *
 * <p>Regression pinned: {@code GasEstimator} already reports
 * {@code usedFallback} and the bridge's error text through its callback
 * (the bridge's {@code dexEstimateGas} forwards the revert reason
 * verbatim), but {@code TxStepsDialog.prepareCurrent} ignored both
 * fields. A step whose estimate had reverted therefore showed a
 * plausible default fee and went READY, the user submitted with the kind
 * default gas limit, and the transaction ran out of gas on-chain with no
 * hint of the underlying revert. The manual gas editor was wired to the
 * gas icon, but {@code onGasIconClick} bailed out when there was no
 * estimate -- precisely the case where the user needs it.
 *
 * <p>Contract: when the estimate fell back, the dialog shows the existing
 * {@code gasEstimateError} string plus the returned error plus the
 * {@code gas-set-manually-hint}; the gas icon opens the editor pre-filled
 * with the kind default whenever no estimate is available. Grep-style
 * source lint (see {@code FirstTimeRestorePasswordTest}); the dialog
 * cannot be instantiated on the JVM.
 */
public class TxStepsGasFallbackContractTest {

    private static final String DIALOG =
            "src/main/java/com/quantumswap/app/view/dialog/TxStepsDialog.java";
    private static final String LANG = "src/main/res/raw/en_us.json";
    private static final String IOS_LANG =
            "../../quantumswap-wallet-ios/QuantumSwapWallet/Resources/en_us.json";

    @Test
    public void estimateFallbackIsSurfacedWithTheReturnedError() throws Exception {
        String src = stripJava(read(DIALOG));
        String body = methodBody(src, "private void prepareCurrent(");
        assertTrue("prepareCurrent's estimate callback must act on usedFallback: a"
                        + " reverted estimate silently produced a default fee and let the"
                        + " user submit a transaction that then ran out of gas.",
                Pattern.compile("if\\s*\\(\\s*usedFallback\\s*\\)").matcher(body).find());
        assertTrue("The fallback notice must use the existing gasEstimateError string.",
                body.contains("\"gasEstimateError\""));
        assertTrue("The fallback notice must include the error text the estimator"
                        + " returned (the node's revert reason).",
                Pattern.compile("usedFallback[\\s\\S]{0,600}?\\berror\\b").matcher(body).find());
        assertTrue("The fallback notice must tell the user the gas limit can be set"
                        + " manually (gas-set-manually-hint).",
                body.contains("\"gas-set-manually-hint\""));
    }

    @Test
    public void gasIconOpensEditorEvenWithoutAnEstimate() throws Exception {
        String src = stripJava(read(DIALOG));
        String body = methodBody(src, "private void onGasIconClick(");
        assertFalse("onGasIconClick must not return early when there is no estimate;"
                        + " that is exactly when the user needs the manual gas editor.",
                Pattern.compile("if\\s*\\(\\s*stepGasLimit\\s*<=\\s*0[^)]*\\)\\s*return;")
                        .matcher(body).find());
        assertTrue("onGasIconClick must pre-fill the editor with the kind default"
                        + " (GasKind.defaultFor) when no estimate is available.",
                body.contains("defaultFor("));
    }

    @Test
    public void hintKeyPresentAndInParityWithIos() throws Exception {
        String expected = "Tap the gas icon to set the gas limit manually.";
        assertEquals("en_us.json must define gas-set-manually-hint", expected,
                langValue(read(LANG), "gas-set-manually-hint"));
        File ios = locate(IOS_LANG, false);
        if (ios != null) {
            String iosLang = new String(Files.readAllBytes(ios.toPath()), StandardCharsets.UTF_8);
            assertEquals("iOS en_us.json must carry the same gas-set-manually-hint"
                    + " (JsonInteractParityTest keeps the key sets in lockstep).",
                    expected, langValue(iosLang, "gas-set-manually-hint"));
        }
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

    private static String langValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("method not found: " + signature, i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, k + 1);
        }
        fail("unbalanced braces in " + signature);
        return null;
    }
}

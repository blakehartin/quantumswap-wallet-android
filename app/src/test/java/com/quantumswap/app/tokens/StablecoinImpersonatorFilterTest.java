package com.quantumswap.app.tokens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.quantumswap.app.api.read.model.AccountTokenSummary;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit-tests for {@link StablecoinImpersonatorFilter}, the
 * suppression filter that hides tokens whose name or symbol mimics a
 * stablecoin / fiat-currency denomination unless their contract
 * address is in {@link RecognizedTokens#ALL}.
 *
 * <p>This suite mirrors the iOS coverage in
 * {@code QuantumSwapWalletTests/TokenFilteringAndLockoutTests.swift}
 * one-for-one and adds two Android-only lockdown tests:
 * <ul>
 *   <li>{@link #homeAndSendApplyFilterBeforeBucketing()} — source-grep
 *       lint that {@code HomeMainFragment.applyFilteredItems} and
 *       {@code SendFragment.setupAssetSpinner} both call
 *       {@link StablecoinImpersonatorFilter#filter(List)} BEFORE any
 *       allow-list bucketing, so impersonators never reach either
 *       home tab nor the Send asset spinner regardless of the "Show
 *       Unrecognized Tokens" toggle.</li>
 *   <li>{@link #androidPatternListMatchesIosPattern()} — parity check
 *       that the Android Java {@code PATTERNS} list and the iOS
 *       Swift {@code patterns} list contain exactly the same set of
 *       quoted lower-case strings. The iOS file is read from a
 *       snapshot at
 *       {@code src/test/resources/code-snapshots/StablecoinImpersonatorFilter.swift}
 *       refreshed by the {@code :app:syncIosImpersonatorFilter}
 *       Gradle task (see {@code app/build.gradle}); the test skips
 *       cleanly with {@link Assume#assumeTrue(boolean)} if the
 *       snapshot is absent so a developer without the iOS sibling
 *       repo checked out still gets a green test run.</li>
 * </ul>
 *
 * <p>All tests are pure JVM (no Android framework dependencies, no
 * Robolectric); the {@link AccountTokenSummary} model is a plain
 * POJO with bean-style setters, and the filter under test is a
 * static utility.
 */
public class StablecoinImpersonatorFilterTest {

    private static AccountTokenSummary tok(String contract, String symbol, String name) {
        AccountTokenSummary t = new AccountTokenSummary();
        t.setContractAddress(contract);
        t.setSymbol(symbol);
        t.setName(name);
        return t;
    }

    @Test
    public void recognizedContractsBypassFilter() {
        AccountTokenSummary heisenAsTether = tok(RecognizedTokens.HEISEN, "USDT", "Tether USD");
        AccountTokenSummary y2qAsDai = tok(RecognizedTokens.Y2Q, "DAI", "Dai Stablecoin");
        List<AccountTokenSummary> result = StablecoinImpersonatorFilter.filter(
                Arrays.asList(heisenAsTether, y2qAsDai));
        assertEquals(
                "Recognized contracts must pass through unchanged even when their "
                        + "labels look like stablecoins.",
                2, result.size());
        assertEquals(RecognizedTokens.HEISEN, result.get(0).getContractAddress());
        assertEquals(RecognizedTokens.Y2Q, result.get(1).getContractAddress());
    }

    @Test
    public void commonImpersonatorSymbolsBlocked() {
        // Each row is a (symbol, name) pair the filter MUST reject.
        // Ticker-style mimics, padded variants, and name-style mimics
        // are all in scope; pattern matching is case-insensitive
        // substring on either field.
        String[][] rows = new String[][] {
                {"USDT", ""}, {"USDC", ""}, {"USDP", ""}, {"USDD", ""},
                {"BUSD", ""}, {"DAI", ""}, {"FRAX", ""}, {"TUSD", ""},
                {"GUSD", ""}, {"PYUSD", ""}, {"FDUSD", ""}, {"LUSD", ""},
                {"USDT.e", ""}, {"USD-Tether", ""}, {"USDT_v2", ""},
                {"XXX", "Tether USD"},
                {"XXX", "Dollar Token"},
                {"EURT", ""}, {"EURC", ""}, {"EURS", ""},
                {"XXX", "Euro Coin"},
                {"XXX", "Yen Coin"},
                {"GBPT", ""}, {"CNY", ""},
                {"XXX", "Stable Hedge"},
        };
        for (String[] row : rows) {
            String sym = row[0];
            String nm = row[1];
            assertTrue(
                    "Expected (symbol=\"" + sym + "\", name=\"" + nm
                            + "\") to be classified as an impersonator.",
                    StablecoinImpersonatorFilter.impersonatesStablecoin(sym, nm));
        }
    }

    @Test
    public void inrAndRupeeAndRupiahBlocked() {
        // Indian rupee impersonators: ticker-style and name-style. The
        // last fixture (IDRT / "Rupiah Token") matches via the spelled-
        // out "rupiah" substring on the *name* field; the ticker IDR
        // is intentionally NOT in the pattern list (see Javadoc on
        // PATTERNS) so a symbol-only "IDRT" with no telltale name
        // would pass — see unrelatedTokensSurvive() for the
        // false-positive guard that pins this design.
        String[][] rows = new String[][] {
                {"INR", ""},
                {"eINR", ""},
                {"cINR", ""},
                {"INRT", ""},
                {"wINR", ""},
                {"XXX", "RupeeCoin"},
                {"XXX", "Digital Rupee"},
                {"eRupee", ""},
                {"XXX", "Rupiah Token"},
                {"IDRT", "Rupiah Token"},
        };
        for (String[] row : rows) {
            String sym = row[0];
            String nm = row[1];
            assertTrue(
                    "Expected (symbol=\"" + sym + "\", name=\"" + nm
                            + "\") to be classified as an INR/Rupee/Rupiah impersonator.",
                    StablecoinImpersonatorFilter.impersonatesStablecoin(sym, nm));
        }
    }

    @Test
    public void unrelatedTokensSurvive() {
        // Negative control. These (symbol, name) pairs MUST pass the
        // filter — they are either explicit allow-list members
        // (HSN/Y2Q tickers without the canonical contract are still
        // benign three-letter strings; here the contract is empty so
        // they go through the normal pattern path) or non-stablecoin
        // tickers, including the false-positive guard set
        // ("Hidro", "Idris") that pins our deliberate decision NOT to
        // add the three-letter "idr" substring to PATTERNS. If a
        // future PR adds "idr" both rows here will fail and force the
        // contributor to revisit the tradeoff documented on PATTERNS.
        String[][] rows = new String[][] {
                {"HSN", "Heisen"},
                {"Y2Q", "Year-2-Quantum"},
                {"BTC", "Bitcoin"},
                {"ETH", "Ether"},
                {"LINK", "Chainlink"},
                {"HID", "Hidro"},
                {"IDR", "Idris"},
        };
        for (String[] row : rows) {
            String sym = row[0];
            String nm = row[1];
            assertFalse(
                    "Expected (symbol=\"" + sym + "\", name=\"" + nm
                            + "\") to NOT be classified as an impersonator.",
                    StablecoinImpersonatorFilter.impersonatesStablecoin(sym, nm));
        }
    }

    @Test
    public void homeAndSendApplyFilterBeforeBucketing() throws IOException {
        // Source-level lockdown. The runtime contract is: every
        // consumer that surfaces tokens to the user MUST funnel
        // through StablecoinImpersonatorFilter.filter(...) BEFORE
        // bucketing into recognized vs unrecognized — otherwise an
        // impersonator would leak into the "Unrecognized Tokens"
        // tab on home or the "Show Unrecognized Tokens" surface on
        // send. We pin this with a grep against the known
        // chokepoints because there is no Robolectric / framework
        // surface to run them against in pure-JVM tests.
        String homeMain = readSource(
                "src/main/java/com/quantumswap/app/view/fragment/HomeMainFragment.java");
        String sendFrag = readSource(
                "src/main/java/com/quantumswap/app/view/fragment/SendFragment.java");

        assertContainsCallSite(homeMain, "HomeMainFragment.java",
                "applyFilteredItems",
                "StablecoinImpersonatorFilter.filter");
        assertContainsCallSite(sendFrag, "SendFragment.java",
                "setupAssetSpinner",
                "StablecoinImpersonatorFilter.filter");
    }

    @Test
    public void androidPatternListMatchesIosPattern() throws IOException {
        // Cross-platform parity lockdown. The two filter files are the
        // documented authority for which fiat-currency labels get
        // suppressed; if the lists drift, a wallet restored across
        // platforms will classify the same indexer token differently
        // and the user will believe they have funds on one platform
        // and not the other (or vice versa). The iOS file is read
        // from the snapshot synced by :app:syncIosImpersonatorFilter;
        // the test skips cleanly if the snapshot is missing (no
        // sibling repo checked out) so a fresh-clone Android-only
        // contributor still gets a green test run.
        File androidFile = new File(
                "src/main/java/com/quantumswap/app/tokens/StablecoinImpersonatorFilter.java");
        File iosSnapshot = new File(
                "src/test/resources/code-snapshots/StablecoinImpersonatorFilter.swift");

        assertTrue(
                "Android filter source must exist at " + androidFile.getAbsolutePath(),
                androidFile.exists());
        Assume.assumeTrue(
                "iOS impersonator-filter snapshot not present at "
                        + iosSnapshot.getAbsolutePath()
                        + " — run :app:syncIosImpersonatorFilter to refresh.",
                iosSnapshot.exists());

        Set<String> androidPatterns = extractPatternsFromAndroidJava(readSource(androidFile));
        Set<String> iosPatterns = extractPatternsFromIosSwift(readSource(iosSnapshot));

        assertFalse(
                "Android pattern list parsed empty — review the regex / Java source format.",
                androidPatterns.isEmpty());
        assertFalse(
                "iOS pattern list parsed empty — review the regex / Swift source format.",
                iosPatterns.isEmpty());

        TreeSet<String> sortedAndroid = new TreeSet<>(androidPatterns);
        TreeSet<String> sortedIos = new TreeSet<>(iosPatterns);
        assertEquals(
                "Android and iOS impersonator-filter pattern lists must match. "
                        + "Android extras: "
                        + difference(sortedAndroid, sortedIos)
                        + " ; iOS extras: "
                        + difference(sortedIos, sortedAndroid),
                sortedIos, sortedAndroid);

        // Spot-check the new INR/Rupee/Rupiah additions are present
        // on both sides; this is technically redundant with the set
        // equality above but yields a much clearer failure message
        // if a future PR drops one of them.
        for (String required : Arrays.asList("inr", "rupee", "rupiah")) {
            assertTrue(
                    "Android PATTERNS missing required entry: " + required,
                    androidPatterns.contains(required));
            assertTrue(
                    "iOS patterns missing required entry: " + required,
                    iosPatterns.contains(required));
        }
    }

    private static String readSource(String relativePath) throws IOException {
        return readSource(new File(relativePath));
    }

    private static String readSource(File f) throws IOException {
        assertTrue("Required source file missing: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void assertContainsCallSite(String body, String fileLabel,
                                               String methodName, String requiredCall) {
        // Find the method DECLARATION (not a call site), which is the
        // first match of `private/public/protected` plus the method
        // name plus '(' on a single line. We then scan its body using
        // brace-depth counting so the assertion works for short
        // helper methods AND for long fragment methods like
        // SendFragment.setupAssetSpinner whose body spans hundreds of
        // lines (a fixed-size window would be too brittle).
        Pattern declPattern = Pattern.compile(
                "(?m)^\\s*(?:private|public|protected)\\s+[^;{}]*?\\b"
                        + Pattern.quote(methodName) + "\\s*\\(");
        Matcher declMatcher = declPattern.matcher(body);
        assertTrue(
                fileLabel + " must declare a method named " + methodName,
                declMatcher.find());

        int openBrace = body.indexOf('{', declMatcher.end());
        assertTrue(
                fileLabel + "." + methodName
                        + " declaration is missing an opening brace.",
                openBrace >= 0);

        int depth = 1;
        int i = openBrace + 1;
        while (i < body.length() && depth > 0) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        assertTrue(
                fileLabel + "." + methodName
                        + " is missing a closing brace at the matching depth.",
                depth == 0);

        String methodBody = body.substring(openBrace, i);
        assertTrue(
                fileLabel + "." + methodName
                        + " must call " + requiredCall
                        + " (chokepoint pinned by StablecoinImpersonatorFilterTest).",
                methodBody.contains(requiredCall));
    }

    private static Set<String> extractPatternsFromAndroidJava(String body) {
        // Anchor the search to the PATTERNS field declaration so we
        // do not pick up unrelated quoted strings elsewhere in the
        // file (e.g. quoted strings inside Javadoc).
        int anchor = body.indexOf("public static final List<String> PATTERNS");
        assertTrue(
                "Could not find PATTERNS declaration in Android filter source.",
                anchor >= 0);
        int end = body.indexOf(';', anchor);
        assertTrue(
                "Could not find end of PATTERNS declaration (missing ';').",
                end > anchor);
        String region = body.substring(anchor, end);
        return extractDoubleQuotedLowerCaseStrings(region);
    }

    private static Set<String> extractPatternsFromIosSwift(String body) {
        int anchor = body.indexOf("static let patterns");
        assertNotNull(body);
        assertTrue(
                "Could not find `static let patterns` declaration in iOS filter source.",
                anchor >= 0);
        // Swift array literal opens at the FIRST '[' after the '='
        // sign — NOT after the field declaration, which immediately
        // contains the `[String]` type annotation that would
        // otherwise be picked up. We seek to '=' first, then the
        // next '[' (the array-literal opener), then the matching ']'.
        int eqIdx = body.indexOf('=', anchor);
        assertTrue(
                "Could not find '=' after `static let patterns` declaration.",
                eqIdx >= 0);
        int openBracket = body.indexOf('[', eqIdx);
        assertTrue(
                "Could not find opening '[' of patterns array.",
                openBracket >= 0);
        int closeBracket = body.indexOf(']', openBracket);
        assertTrue(
                "Could not find closing ']' of patterns array.",
                closeBracket > openBracket);
        String region = body.substring(openBracket, closeBracket + 1);
        return extractDoubleQuotedLowerCaseStrings(region);
    }

    private static Set<String> extractDoubleQuotedLowerCaseStrings(String region) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([a-z0-9]+)\"").matcher(region);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static List<String> difference(Set<String> a, Set<String> b) {
        List<String> diff = new ArrayList<>();
        for (String s : a) {
            if (!b.contains(s)) diff.add(s);
        }
        return diff;
    }
}

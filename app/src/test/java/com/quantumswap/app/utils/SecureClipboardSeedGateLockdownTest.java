package com.quantumswap.app.utils;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level lockdown for the API-29-32 seed-clipboard gate.
 *
 * <p>SEC-05 rationale (cross-platform parity with iOS):
 * Android API 29-32 (Android 10-12) cannot mark a {@code ClipData}
 * with the {@code android.content.extra.IS_SENSITIVE} extra. Without
 * that flag a copied seed phrase is visible to vendor clipboard
 * managers, accessibility services, and clipboard-history dumps for
 * the entire 30-second auto-clear window. The product policy is to
 * remove the seed-copy affordances entirely on those OS versions and
 * require the user to write the seed down by hand instead. iOS uses
 * {@code .localOnly} + {@code .expirationDate} which gives OS-level
 * guarantees on every supported version, so this gate has no iOS
 * analogue; the parity dimension on iOS is "no seed-copy weakening
 * exists" rather than "the same gate."
 *
 * <p>This test pins three structural invariants:
 * <ol>
 *   <li>{@link SecureClipboard#isSeedClipboardCopyHardened()} returns
 *       a strict {@code SDK_INT &gt;= TIRAMISU} comparison (i.e. API
 *       33+, Android 13+). Any future loosening of the bound
 *       (e.g. {@code &gt; 28}) is a build-blocking review failure.</li>
 *   <li>{@code RevealWalletFragment} both calls
 *       {@code isSeedClipboardCopyHardened()} AND wraps the
 *       {@code revealCopyClickListener} setOnClickListener wiring in
 *       a guard. Skipping either pin re-introduces the SEC-05
 *       exposure.</li>
 *   <li>{@code HomeWalletFragment} (the new-wallet seed-display
 *       screen) does the same for its
 *       {@code homeCopyClickListener}.</li>
 * </ol>
 *
 * <p>This is a grep-style lint, not a runtime test, because the codebase
 * deliberately avoids Robolectric (per the project README testing
 * note) and {@code Build.VERSION.SDK_INT} on the JVM stub returns 0,
 * which would force every test path through a single API-level
 * branch.
 */
public class SecureClipboardSeedGateLockdownTest {

    private static final String SOURCE_ROOT =
            "src/main/java/com/quantumswap/app";

    /**
     * Pattern that matches the strict SDK_INT comparison the helper
     * is supposed to use. The comparison MUST be against
     * {@code Build.VERSION_CODES.TIRAMISU} (API 33), not against a
     * raw integer literal that might drift away from the actual
     * IS_SENSITIVE-supporting platform.
     */
    private static final Pattern HARDENING_BOUND = Pattern.compile(
            "Build\\.VERSION\\.SDK_INT\\s*>=\\s*Build\\.VERSION_CODES\\.TIRAMISU");

    /**
     * Pattern that matches the helper-call gate any seed-copy call
     * site must contain. The call site stores the result in a
     * boolean local that subsequent {@code if (...)} guards consume.
     */
    private static final Pattern GATE_CALL = Pattern.compile(
            "SecureClipboard\\s*\\.\\s*isSeedClipboardCopyHardened\\s*\\(\\s*\\)");

    @Test
    public void hardeningBoundIsAtTiramisu() throws Exception {
        File srcFile = new File(SOURCE_ROOT + "/utils/SecureClipboard.java");
        Assume.assumeTrue(
                "source not found at " + srcFile.getAbsolutePath()
                        + "; running outside the app module CWD.",
                srcFile.exists());
        String src = readUtf8(srcFile);
        String stripped = stripJavaComments(src);
        Matcher m = HARDENING_BOUND.matcher(stripped);
        assertTrue(
                "SecureClipboard.isSeedClipboardCopyHardened() must compare "
                        + "Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU "
                        + "(API 33). A weaker bound would re-introduce SEC-05 "
                        + "by allowing seed copies on devices that lack the "
                        + "IS_SENSITIVE ClipDescription extra.",
                m.find());
    }

    @Test
    public void revealFragmentGuardsSeedCopyWiring() throws Exception {
        File f = new File(SOURCE_ROOT
                + "/view/fragment/RevealWalletFragment.java");
        Assume.assumeTrue(f.exists());
        assertCallSiteIsGated(f, "revealCopyClickListener");
    }

    @Test
    public void homeFragmentGuardsSeedCopyWiring() throws Exception {
        File f = new File(SOURCE_ROOT
                + "/view/fragment/HomeWalletFragment.java");
        Assume.assumeTrue(f.exists());
        assertCallSiteIsGated(f, "homeCopyClickListener");
    }

    @Test
    public void noBareSeedCopyClickListenerOutsideGate() throws Exception {
        // Defense-in-depth: the two known seed-copy listener
        // identifiers must not appear in setOnClickListener calls in
        // any *other* file. If a future refactor moves the click
        // wiring into a helper, this catches the move so the new
        // location can be added to the lockdown explicitly.
        File mainRoot = new File(SOURCE_ROOT);
        Assume.assumeTrue(mainRoot.exists());
        Pattern bareWiring = Pattern.compile(
                "setOnClickListener\\s*\\(\\s*"
                        + "(?:revealCopyClickListener|homeCopyClickListener)\\s*\\)");
        Files.walk(mainRoot.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return !name.equals("RevealWalletFragment.java")
                            && !name.equals("HomeWalletFragment.java");
                })
                .forEach(p -> {
                    try {
                        String src = stripJavaComments(
                                new String(Files.readAllBytes(p),
                                        StandardCharsets.UTF_8));
                        if (bareWiring.matcher(src).find()) {
                            fail("Seed-copy click listener wiring found "
                                    + "outside the two known gated files: "
                                    + p + ". Add the new file to the "
                                    + "SEC-05 lockdown or route through a "
                                    + "shared helper that performs the gate.");
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * For a given fragment file, assert that:
     * <ol>
     *   <li>{@link SecureClipboard#isSeedClipboardCopyHardened()} is
     *       called somewhere in the file (so the file knows about
     *       the gate).</li>
     *   <li>The {@code setOnClickListener(<listenerName>)} wiring
     *       sits inside an {@code if (...)} block (cheap proxy: the
     *       listener-bind line is preceded within 8 lines by an
     *       {@code if } that consumes a boolean).</li>
     * </ol>
     */
    private static void assertCallSiteIsGated(File f, String listenerName)
            throws Exception {
        String src = readUtf8(f);
        String stripped = stripJavaComments(src);

        Matcher gate = GATE_CALL.matcher(stripped);
        assertTrue(f.getName()
                        + " must call SecureClipboard.isSeedClipboardCopyHardened()"
                        + " before wiring " + listenerName
                        + ". Without the gate the SEC-05 fix is bypassed on"
                        + " API 29-32.",
                gate.find());

        Pattern bind = Pattern.compile(
                "setOnClickListener\\s*\\(\\s*" + Pattern.quote(listenerName)
                        + "\\s*\\)");
        Matcher bindMatcher = bind.matcher(stripped);
        assertTrue("Seed-copy click listener " + listenerName
                + " is not wired in " + f.getName() + " at all -- did the "
                + "control name change? Update this lockdown.", bindMatcher.find());

        // Walk back from the bind site looking for an enclosing
        // if (...) within 12 lines. The grep is intentionally loose;
        // its job is to catch the regression "someone removed the if
        // and re-bound the listener unconditionally."
        int bindOffset = bindMatcher.start();
        String preamble = stripped.substring(0, bindOffset);
        int lastIfIdx = preamble.lastIndexOf("if (");
        int lastNewlineBeforeBind = preamble.lastIndexOf('\n', bindOffset);
        // Count how many newlines sit between the most recent "if (" and
        // the bind line. <= 12 lines counts as "the bind is gated."
        int newlinesBetween = 0;
        if (lastIfIdx >= 0) {
            for (int i = lastIfIdx; i < lastNewlineBeforeBind && i < preamble.length(); i++) {
                if (preamble.charAt(i) == '\n') newlinesBetween++;
            }
        }
        assertNotEquals("No enclosing if (...) found near " + listenerName
                + " bind in " + f.getName() + ".", -1, lastIfIdx);
        assertTrue("Bind of " + listenerName + " in " + f.getName()
                        + " is too far from the most recent if (...) ("
                        + newlinesBetween + " lines) -- the gate may have "
                        + "been removed.",
                newlinesBetween <= 12);
    }

    private static String readUtf8(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String stripJavaComments(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)//[^\n]*", "");
        return s;
    }
}

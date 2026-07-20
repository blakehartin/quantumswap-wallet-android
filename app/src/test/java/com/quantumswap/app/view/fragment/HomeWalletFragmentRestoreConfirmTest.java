package com.quantumswap.app.view.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.quantumswap.app.utils.CoinUtils;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Acceptance suite for the Confirm-Wallet step of the
 * Restore-Wallet flow. Mirrors iOS {@code ConfirmWalletViewControllerTests}.
 *
 * <p>This suite is structured around what we can deterministically
 * assert from a JVM unit-test environment without booting an Android
 * Activity. The exhaustive UI-side acceptance (back-arrow restores
 * grid, idempotency disable on rapid taps, balance load shows toast)
 * is covered by the staged QA plan attached to the PR; what we cover
 * here is the static contract:
 *
 * <ol>
 *   <li><b>M.4.1 back-button preserves typed phrase</b> -- the
 *   {@link HomeWalletFragment} class must hold an
 *   {@code enteredRestorePhrase} (or equivalent) field that is NOT
 *   wiped on the back transition. Verified via reflection on field
 *   names; this catches a regression where a refactor removes the
 *   field or renames it without updating the back-arrow handler.</li>
 *
 *   <li><b>M.4.2 two-layer idempotency guard</b> -- the
 *   {@code confirmWalletSaveInProgress} boolean field must exist AND
 *   the source must wire a reset path
 *   ({@code resetConfirmWalletSaveGuard}) that is callable. We assert
 *   both via reflection so any rename collapses the test.</li>
 *
 *   <li><b>M.4.4 visible balance label format byte-matches iOS</b> --
 *   the value rendered by {@code CoinUtils.formatWei} for several
 *   golden inputs is exactly the iOS string (numeric, no "Coins"
 *   suffix). The fragment is responsible for NOT appending the
 *   coinsLabel to the value TextView.</li>
 *
 *   <li><b>M.4.5 tap-to-copy on Confirm address row</b> -- the
 *   private helper {@code wireConfirmAddressTapToCopy} exists. This
 *   is a structural pin: removing the helper or renaming it without
 *   updating the populate-balance call site will break the test.</li>
 *
 *   <li><b>M.4.7 'Balance on &lt;network&gt;' a11y caption</b> -- the
 *   private helper {@code currentNetworkDisplayName} exists. Same
 *   structural pin as M.4.5.</li>
 *
 *   <li><b>M.4.8 expanded reviewer comment</b> -- the
 *   {@code populateConfirmWalletAddressAndBalance} javadoc must
 *   reference at least the four threat-model anchors enumerated in
 *   the comment block. Verified by source-level grep against
 *   {@code HomeWalletFragment.java} (read from the project sources).
 *   This catches a future maintainer who deletes the comment block.</li>
 * </ol>
 */
public class HomeWalletFragmentRestoreConfirmTest {

    private static final Class<?> FRAG = HomeWalletFragment.class;

    @Test
    public void m4_1_backButtonPreservesTypedPhrase_fieldExists() throws Exception {
        // Restore-flow must carry the typed phrase across the
        // back-and-forth so users do not lose 24 typed words. We
        // accept any of the historical names the field has had:
        // tempSeedWords / enteredRestorePhrase / restorePhraseDraft.
        boolean ok = hasFieldOneOf(FRAG,
                "tempSeedWords",
                "enteredRestorePhrase",
                "restorePhraseDraft");
        assertTrue("Confirm-Wallet back transition requires a "
                + "preserved-phrase field; none of "
                + "tempSeedWords / enteredRestorePhrase / "
                + "restorePhraseDraft found.", ok);
    }

    @Test
    public void m4_2_idempotencyGuard_fieldAndResetExist() throws Exception {
        Field guard = FRAG.getDeclaredField("confirmWalletSaveInProgress");
        assertEquals("Idempotency guard must be a boolean.",
                boolean.class, guard.getType());

        Method reset = findDeclaredMethod(FRAG, "resetConfirmWalletSaveGuard");
        assertNotNull("Confirm-Wallet save guard requires a reset path.", reset);
    }

    @Test
    public void m4_4_balanceLabelFormatByteMatchesIos_zeroIsBareZero() {
        // iOS CoinUtils.formatWei("0") returns "0" (no decimals, no
        // suffix). The fragment renders that string into the balance
        // value TextView verbatim.
        assertEquals("0", CoinUtils.formatWei("0"));
        assertEquals("0", CoinUtils.formatWei(""));
        assertEquals("0", CoinUtils.formatWei(null));
    }

    @Test
    public void m4_4_balanceLabelFormatByteMatchesIos_oneCoin() {
        // 10^18 wei = "1" QC -- iOS strips trailing fractional zeros
        // and the trailing dot. Android must produce identical output.
        String oneCoinWei = "1000000000000000000";
        assertEquals("1", CoinUtils.formatWei(oneCoinWei));
    }

    @Test
    public void m4_4_balanceLabelFormatByteMatchesIos_fractional() {
        // 1.5 QC = 1.5 * 10^18 wei. iOS preserves the fractional
        // part verbatim with a single decimal point.
        String oneAndHalfWei = "1500000000000000000";
        String formatted = CoinUtils.formatWei(oneAndHalfWei);
        assertEquals("1.5", formatted);
    }

    @Test
    public void m4_4_balanceLabelFormatByteMatchesIos_subUnit() {
        // 0.001 QC = 10^15 wei. Leading "0." preserved; no scientific
        // notation. (iOS uses the same digit-string formatter.)
        String tinyWei = "1000000000000000";
        assertEquals("0.001", CoinUtils.formatWei(tinyWei));
    }

    @Test
    public void m4_5_tapToCopy_helperExists() {
        Method helper = findDeclaredMethod(FRAG, "wireConfirmAddressTapToCopy");
        assertNotNull("M.4.5 requires wireConfirmAddressTapToCopy() helper.", helper);
    }

    @Test
    public void m4_7_balanceOnNetworkCaption_helperExists() {
        Method helper = findDeclaredMethod(FRAG, "currentNetworkDisplayName");
        assertNotNull("M.4.7 requires currentNetworkDisplayName() helper.", helper);
        assertEquals("Helper must return a String network name (or null).",
                String.class, helper.getReturnType());
    }

    @Test
    public void m4_8_expandedReviewerComment_anchorsPresent() throws Exception {
        // Source-level grep against the project's main java file. We
        // do this rather than a synthetic class doc test because the
        // comment block is the deliverable -- its presence is the
        // assertion.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found in expected"
                + " path; running outside the app module CWD.", f.exists());

        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // The expanded comment block on the field declaration MUST
        // mention each of these anchors.
        String[] requiredAnchors = new String[] {
                "Why a balance fetch at all",
                "Why the two-layer idempotency guard",
                "Why no full-screen WaitDialog",
                "Why is balance label format byte-equal to iOS",
                "Why does the address row support tap-to-copy",
                "Why does the back button preserve the typed phrase",
        };
        for (String anchor : requiredAnchors) {
            assertTrue("Expanded reviewer comment is missing anchor: \""
                            + anchor + "\". Did a refactor remove the "
                            + "Confirm-Wallet rationale block?",
                    src.contains(anchor));
        }
    }

    @Test
    public void confirmWalletBalanceTaskField_existsAndIsCancellable() throws Exception {
        // Confirm-Wallet flow used to leave a stale balance fetch
        // running when the user back-navigated to Seed-Edit and
        // returned, or pressed Next while the spinner was still
        // visible. The stale callback could re-arm the inline
        // balance progress bar on a now-hidden panel ("refresh
        // keeps spinning" symptom). Pin the in-flight task field
        // and the centralised cancel + spinner-hide helpers so a
        // future refactor cannot silently drop them.
        Field taskField = FRAG.getDeclaredField("confirmWalletBalanceTask");
        assertEquals("In-flight balance task must be the cancellable "
                        + "AccountBalanceRestTask type so the helper can "
                        + "supersede it.",
                "com.quantumswap.app.asynctask.read.AccountBalanceRestTask",
                taskField.getType().getName());

        Method cancelHelper = findDeclaredMethod(FRAG,
                "cancelInFlightConfirmWalletBalanceTask");
        assertNotNull("cancelInFlightConfirmWalletBalanceTask() helper "
                + "is the single source of truth for cancelling a stale "
                + "balance fetch; it must exist.", cancelHelper);

        Method hideHelper = findDeclaredMethod(FRAG,
                "hideConfirmWalletBalanceSpinner");
        assertNotNull("hideConfirmWalletBalanceSpinner() helper centralises "
                + "the visibility flip so no branch can leave the inline "
                + "progress bar VISIBLE.", hideHelper);
    }

    @Test
    public void confirmWalletBalanceTask_supersededOnNavigationAndDestroy() throws Exception {
        // Source-level pin: every navigation away from the Confirm-
        // Wallet panel (Back, Next) and the fragment teardown path
        // (onDestroyView) must invoke
        // cancelInFlightConfirmWalletBalanceTask. A missed call
        // would re-introduce the stale-callback regression.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // At least 3 call sites: Back handler, Next handler, onDestroyView.
        int hits = 0;
        int idx = 0;
        while (true) {
            int next = src.indexOf("cancelInFlightConfirmWalletBalanceTask", idx);
            if (next < 0) break;
            hits++;
            idx = next + 1;
        }
        // The declaration itself counts as one match. We need three
        // additional invocations (Back, Next, onDestroyView) plus
        // the declaration = 4 minimum.
        assertTrue("cancelInFlightConfirmWalletBalanceTask must be "
                        + "invoked from Back, Next, AND onDestroyView "
                        + "(plus the helper declaration). Found " + hits
                        + " reference(s); expected at least 4.",
                hits >= 4);
    }

    @Test
    public void saveWalletPasswordNotSet_reArmsNextButton() throws Exception {
        // Regression guard: the "Wallet password is not set" early
        // return inside saveWalletFromSeedWords used to skip
        // resetConfirmWalletSaveGuard, leaving the Confirm-Wallet
        // Next button permanently disabled. The user could see the
        // error dialog but had no way to retry except by tearing
        // down the fragment.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // Find the "Wallet password is not set" string and assert
        // resetConfirmWalletSaveGuard is invoked within the
        // surrounding ~1200 chars (i.e. inside the same branch).
        // Window is wider than the 600 chars earlier draft used so
        // a comment block between the error dialog and the reset
        // call cannot push the call out of the scan range.
        int notSetIdx = src.indexOf("Wallet password is not set");
        assertTrue("'Wallet password is not set' branch not found.",
                notSetIdx >= 0);
        String window = src.substring(notSetIdx,
                Math.min(src.length(), notSetIdx + 1200));
        assertTrue("'Wallet password is not set' branch must call "
                        + "resetConfirmWalletSaveGuard() before its return "
                        + "so the user can retry instead of being stranded "
                        + "with a permanently-disabled Next button.",
                window.contains("resetConfirmWalletSaveGuard"));
    }

    @Test
    public void promptStrongboxPasswordEarlyReturns_reArmNextButton() throws Exception {
        // promptStrongboxPasswordForSave's null-ctx and null-
        // secureStorage guards used to silently return without
        // resetting confirmWalletSaveInProgress. Pin the fix so a
        // future refactor cannot strand Next disabled when the
        // fragment is mid-detach.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        int methodIdx = src.indexOf("promptStrongboxPasswordForSave(final String[] seedWords");
        assertTrue("promptStrongboxPasswordForSave method signature not found.",
                methodIdx >= 0);
        // Walk the brace depth from the opening '{' after the
        // signature to find the matching close, so the window
        // matches the entire method body and not a guess at its
        // length. The method spans ~150 lines (unlock dialog setup
        // plus password verify thread plus close button) so a
        // fixed-byte window is fragile.
        int braceOpen = src.indexOf('{', methodIdx);
        assertTrue("promptStrongboxPasswordForSave opening brace not found.",
                braceOpen >= 0);
        int depth = 0;
        int braceClose = -1;
        for (int i = braceOpen; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { braceClose = i; break; }
            }
        }
        assertTrue("promptStrongboxPasswordForSave brace match failed.",
                braceClose > braceOpen);
        String body = src.substring(braceOpen, braceClose);

        // We expect at least 4 invocations of resetConfirmWalletSaveGuard
        // inside the method body: null-ctx guard, null-secureStorage
        // guard, close-button click handler, and the outer catch
        // block. A drop below 4 means a recovery path was lost.
        int hits = 0;
        int idx = 0;
        while (true) {
            int next = body.indexOf("resetConfirmWalletSaveGuard", idx);
            if (next < 0) break;
            hits++;
            idx = next + 1;
        }
        assertTrue("promptStrongboxPasswordForSave's null-ctx and null-"
                        + "secureStorage guards must each call "
                        + "resetConfirmWalletSaveGuard() before returning "
                        + "(plus the existing close-button + catch paths). "
                        + "Found " + hits + " call(s); expected at least 4.",
                hits >= 4);
    }

    @Test
    public void confirmWalletDeriveWaitDialog_dismissedWithoutActivityRef() throws Exception {
        // The "wait wallet open" overlay shown while
        // walletFromPhrase derives the address used to be dismissed
        // via getActivity().runOnUiThread(...) and silently leak if
        // getActivity() returned null at callback time (config
        // change, fragment swap mid-derive). The fix routes the
        // dismissal through a Handler bound to the main looper so
        // the dismiss + error path always runs. Pin the source
        // marker so a refactor cannot silently revert.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // The fix introduces a Handler local named
        // mainHandlerForDerive. Pin it.
        assertTrue("showConfirmWalletScreen must dismiss the derive "
                        + "WaitDialog through a main-looper Handler "
                        + "(mainHandlerForDerive) so the dismissal does not "
                        + "depend on getActivity() being non-null at "
                        + "callback time.",
                src.contains("mainHandlerForDerive"));
        // And the derive worker must NOT use getActivity().runOnUiThread.
        // Find the showConfirmWalletScreen method body and assert.
        int methodIdx = src.indexOf("showConfirmWalletScreen(final String[] seedWords");
        assertTrue("showConfirmWalletScreen signature not found.", methodIdx >= 0);
        // Find end of method by tracking brace depth from the
        // opening '{' after the signature. The method body is large
        // (~260 lines, mostly nested click handlers and the derive
        // worker) so an unbounded scan is needed; a fixed-byte cap
        // would silently truncate the body and let
        // getActivity().runOnUiThread re-appear unnoticed.
        int braceOpen = src.indexOf('{', methodIdx);
        assertTrue("showConfirmWalletScreen opening brace not found.", braceOpen >= 0);
        int depth = 0;
        int braceClose = -1;
        for (int i = braceOpen; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { braceClose = i; break; }
            }
        }
        assertTrue("showConfirmWalletScreen body brace match failed.",
                braceClose > braceOpen);
        String methodBody = src.substring(braceOpen, braceClose);
        assertFalse("showConfirmWalletScreen derive worker must NOT call "
                        + "getActivity().runOnUiThread — that gates "
                        + "dismissal on a non-null activity reference and "
                        + "regresses the WaitDialog leak fix.",
                methodBody.contains("getActivity().runOnUiThread"));
    }

    @Test
    public void mismatchCheckGatedToCreateFlowOnly() throws Exception {
        // Restore-flow regression guard. tempSeedWords doubles as a
        // back-navigation cache for the restore flow (see M.4.1
        // above), which means it is non-null on the second visit to
        // the seed-edit screen. The "does not match the original seed
        // word" error must NOT fire in that situation: the user typed
        // those words themselves, there is no "original" to compare
        // against, and re-firing the error blocks every legitimate
        // edit-after-back-press.
        //
        // We assert the gate at the source level: any reference to
        // getSeedWordMismatchByErrors must be enclosed by a check
        // that excludes the restore flow. The simplest stable
        // signature is a nearby reference to
        // isRestoreFromSeedFlow (the boolean introduced by the fix).
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        int mismatchIdx = src.indexOf("getSeedWordMismatchByErrors");
        assertTrue("getSeedWordMismatchByErrors call site not found; "
                        + "rename or removal must update this test.",
                mismatchIdx >= 0);

        // Look back ~600 chars to find the if-guard that opens the
        // mismatch branch. The fix's guard is
        // \"!isRestoreFromSeedFlow\" so we look for that token.
        int windowStart = Math.max(0, mismatchIdx - 600);
        String guardWindow = src.substring(windowStart, mismatchIdx);
        assertTrue("Restore-flow regression: the seed-word mismatch "
                        + "check must be gated by !isRestoreFromSeedFlow "
                        + "(or equivalent create-flow-only check). "
                        + "Without this gate, editing a word after the "
                        + "user back-presses from Confirm-Wallet falsely "
                        + "triggers the \"does not match the original seed "
                        + "word\" error in the restore path.",
                guardWindow.contains("!isRestoreFromSeedFlow"));

        // Belt-and-suspenders: ensure the boolean itself is derived
        // from the radio button that picks the create vs restore
        // mode (radio_1 = restore). A naive refactor that wires
        // isRestoreFromSeedFlow to the wrong source would silently
        // invert the gate.
        assertTrue("isRestoreFromSeedFlow must be derived from "
                        + "homeCreateRestoreWalletRadioButton_1.isChecked() "
                        + "(radio_1 is the restore-from-seed option). "
                        + "Inverting the source would re-introduce the "
                        + "false-positive mismatch error in the restore "
                        + "path.",
                Pattern.compile(
                        "isRestoreFromSeedFlow\\s*=\\s*\\n?\\s*"
                                + "homeCreateRestoreWalletRadioButton_1\\.isChecked\\(\\)",
                        Pattern.DOTALL).matcher(src).find());
    }

    @Test
    public void m4_4_visibleValueDoesNotAppendCoinsSuffix_inSourceContract() throws Exception {
        // Pin the contract that the populate path renders the bare
        // formatWei output. If a future maintainer reintroduces the
        // " + coinsLabel" concatenation on the visible TextView,
        // this test catches it at PR time.
        java.io.File f = new java.io.File("src/main/java/com/quantumswap/"
                + "app/view/fragment/HomeWalletFragment.java");
        org.junit.Assume.assumeTrue("HomeWalletFragment.java not found.", f.exists());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // Find the populate method body and search for any
        // setText(...) call that concatenates coinsLabel onto the
        // value. Heuristic: a regex looking for setText with
        // coinsLabel in the same expression. The a11y caption
        // (contentDescription) IS allowed to mention coinsLabel.
        Pattern bad = Pattern.compile(
                "homeConfirmWalletBalanceValueTextView\\s*\\.\\s*setText\\s*\\([^)]*coinsLabel",
                Pattern.DOTALL);
        assertTrue("M.4.4 violation: visible balance setText must NOT "
                        + "concatenate coinsLabel; only the a11y "
                        + "contentDescription may.",
                !bad.matcher(src).find());
    }

    private static boolean hasFieldOneOf(Class<?> c, String... names) {
        for (String n : names) {
            try {
                c.getDeclaredField(n);
                return true;
            } catch (NoSuchFieldException ignore) { }
        }
        return false;
    }

    private static Method findDeclaredMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}

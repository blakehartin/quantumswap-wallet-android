package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * First-time restore must not overwrite the app unlock password, and every
 * first-time setup path must end at the unlock screen.
 *
 * <p>Background. On a fresh install the onboarding wizard collects an app
 * password on the Set-Password screen. The strongbox password is defined by
 * the FIRST {@code SecureStorage.createMainKey(...)} call and is never
 * rotated afterwards ({@code UnlockCoordinator.persist} re-uses the
 * {@code passwordWrap} envelope verbatim). The regression class this test
 * pins:
 *
 * <ol>
 *   <li>The Set-Password "Next" handler must actually capture the typed
 *   password into the {@code walletPassword} field. Historically it only
 *   validated the EditTexts, so the two restore paths reached
 *   {@code createMainKey} with {@code walletPassword == null} and
 *   bootstrapped the strongbox with a different password.</li>
 *   <li>Restore-from-FILE ({@code performRestoreFromUri}) must never feed
 *   the BACKUP FILE password into the strongbox — not to
 *   {@code createMainKey}, not to {@code unlock}, not to
 *   {@code saveWallet}. The backup password decrypts the {@code .wallet}
 *   envelope and nothing else. The overwrite shipped as
 *   {@code createMainKey(getContext(), backupPassword)}, silently
 *   discarding the password the user had just chosen.</li>
 *   <li>Restore-from-FOLDER must prefer the onboarding password over
 *   re-prompting: {@code ensureStrongboxReadyForRestore} must consult
 *   {@code walletPassword} before showing the unlock dialog, because on a
 *   fresh install that dialog's {@code !isInitialized} branch CREATES the
 *   strongbox from whatever is typed — an "Unlock" dialog must not be a
 *   silent create surface when the user already chose a password.</li>
 *   <li>Both restore paths must end at the unlock gate
 *   ({@code HomeActivity.requirePasswordReentryThenNavigate}) on
 *   first-time setup, exactly like the create / restore-from-seed paths
 *   already do via {@code finishBackupAndNavigateToHome} — so a password
 *   mismatch surfaces immediately, not on the next cold start.</li>
 * </ol>
 *
 * <p>This is a grep-style source lint (the codebase deliberately avoids
 * Robolectric, per the README testing note), in the same shape as
 * {@code BackupRestoreLimiterRecordingTest} and the M.4 tests in
 * {@code HomeWalletFragmentRestoreConfirmTest}: locate a method body by
 * brace matching, then assert on its content with comments stripped.
 * Sibling guards: {@code BackupRestoreLimiterRecordingTest},
 * {@code WalletCreationPasswordWhitespaceTest},
 * {@code SecureWindowLockdownTest}.
 *
 * <p>The compatibility constraint for existing users is pinned too: the fix
 * must not touch the strongbox layer. {@code strongboxFormatUntouched}
 * asserts persist() still never rotates the password envelope and that no
 * new strongbox-creation call site appears outside
 * {@code SecureStorage.createMainKey}.
 */
public class FirstTimeRestorePasswordTest {

    private static final String FRAGMENT_PATH =
            "src/main/java/com/quantumswap/app/view/fragment/HomeWalletFragment.java";
    private static final String COORDINATOR_PATH =
            "src/main/java/com/quantumswap/app/keystorage/UnlockCoordinator.java";
    private static final String SECURE_STORAGE_PATH =
            "src/main/java/com/quantumswap/app/keystorage/SecureStorage.java";
    private static final String MAIN_JAVA_ROOT = "src/main/java/com/quantumswap/app";

    // ---------------------------------------------------------------
    // 1. Onboarding Set-Password Next handler captures the password
    // ---------------------------------------------------------------

    @Test
    public void onboardingNextHandlerCapturesWalletPassword() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String handler = bodyAfter(src, "homeSetWalletNextButton.setOnClickListener");

        assertTrue("The Set-Password Next handler must capture the validated"
                        + " password into the walletPassword field. Without this"
                        + " assignment the restore-from-file and restore-from-folder"
                        + " paths reach SecureStorage.createMainKey with"
                        + " walletPassword == null and bootstrap the strongbox with"
                        + " a DIFFERENT password than the one the user just chose.",
                Pattern.compile("walletPassword\\s*=").matcher(handler).find());

        int assign = indexOfRegex(handler, "walletPassword\\s*=");
        int advance = handler.indexOf("showBackupPromptIfNeeded");
        assertTrue("showBackupPromptIfNeeded call not found in the Set-Password"
                + " Next handler; the wizard-advance shape changed and this test"
                + " needs updating alongside it.", advance >= 0);
        assertTrue("walletPassword must be assigned BEFORE the wizard advances"
                        + " (showBackupPromptIfNeeded); assigning it later leaves a"
                        + " window where a restore path can run with a null app"
                        + " password.",
                assign >= 0 && assign < advance);
    }

    // ---------------------------------------------------------------
    // 2 + 3. Restore-from-FILE keeps the two passwords separate
    // ---------------------------------------------------------------

    @Test
    public void fileRestoreNeverBootstrapsStrongboxWithBackupPassword() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void performRestoreFromUri(");

        assertFalse("performRestoreFromUri must NOT create the strongbox main"
                        + " key from the BACKUP FILE password. createMainKey defines"
                        + " the app unlock password permanently (persist() never"
                        + " rotates it), so this call silently overwrites the"
                        + " password chosen on the Set-Password screen.",
                Pattern.compile("createMainKey\\s*\\([\\s\\S]{0,60}?backupPassword").matcher(body).find());

        assertFalse("performRestoreFromUri must NOT unlock the strongbox with"
                        + " the BACKUP FILE password; the strongbox password is the"
                        + " app password, sourced from onboarding"
                        + " (walletPassword) or a verified re-prompt"
                        + " (pendingStrongboxPassword).",
                Pattern.compile("\\.unlock\\s*\\([\\s\\S]{0,60}?backupPassword").matcher(body).find());
    }

    @Test
    public void fileRestoreSavesWalletWithStrongboxPassword() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void performRestoreFromUri(");

        assertFalse("performRestoreFromUri must NOT pass the BACKUP FILE"
                        + " password to saveWallet: persist() derives the write key"
                        + " from that argument, so with an already-initialized"
                        + " strongbox the write fails (and counts against the"
                        + " brute-force limiter) unless the backup password happens"
                        + " to equal the app password.",
                Pattern.compile("saveWallet\\s*\\([\\s\\S]{0,200}?backupPassword").matcher(body).find());

        assertTrue("performRestoreFromUri must source the strongbox write"
                        + " password from pendingStrongboxPassword — the same"
                        + " separation the folder-restore path (attemptBatchDecrypt)"
                        + " already uses — keeping the backup password strictly at"
                        + " the file-decrypt boundary (CloudBackupManager"
                        + ".decryptWallet).",
                body.contains("pendingStrongboxPassword"));
    }

    // ---------------------------------------------------------------
    // 4. Restore-from-FOLDER prefers the onboarding password
    // ---------------------------------------------------------------

    @Test
    public void folderRestorePrefersOnboardingPasswordOverDialog() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void ensureStrongboxReadyForRestore(");

        int consult = body.indexOf("walletPassword");
        int dialog = body.indexOf("showStrongboxRestoreUnlockDialog");
        assertTrue("ensureStrongboxReadyForRestore must consult the onboarding"
                        + " walletPassword before falling back to the unlock dialog."
                        + " On a fresh install that dialog's !isInitialized branch"
                        + " CREATES the strongbox from whatever is typed, so"
                        + " skipping the walletPassword check turns an \"Unlock\""
                        + " dialog into a silent create surface that discards the"
                        + " user's chosen password.",
                consult >= 0);
        assertTrue("showStrongboxRestoreUnlockDialog call not found in"
                + " ensureStrongboxReadyForRestore; the folder-restore shape"
                + " changed and this test needs updating alongside it.", dialog >= 0);
        assertTrue("The walletPassword first-time short-circuit must come BEFORE"
                        + " the dialog fallback in ensureStrongboxReadyForRestore.",
                consult < dialog);
    }

    // ---------------------------------------------------------------
    // 5 + 6. First-time restores end at the unlock gate
    // ---------------------------------------------------------------

    @Test
    public void fileRestoreFirstTimeEndsAtUnlockGate() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));

        String helper = methodBody(src, "private void completeRestoreNavigation(");
        assertTrue("completeRestoreNavigation must route first-time restores"
                        + " through HomeActivity.requirePasswordReentryThenNavigate"
                        + " — the same forced-modal unlock gate the create and"
                        + " restore-from-seed paths use via"
                        + " finishBackupAndNavigateToHome — so a password mismatch"
                        + " surfaces before the user leaves onboarding, not on the"
                        + " next cold start.",
                helper.contains("requirePasswordReentryThenNavigate"));

        String body = methodBody(src, "private void performRestoreFromUri(");
        assertTrue("performRestoreFromUri's success block must complete via"
                        + " completeRestoreNavigation (which applies the first-time"
                        + " unlock gate), not by navigating directly.",
                body.contains("completeRestoreNavigation"));
        assertFalse("performRestoreFromUri must not call"
                        + " onHomeWalletCompleteByHomeMain directly — direct"
                        + " navigation bypasses the first-time unlock gate.",
                body.contains("onHomeWalletCompleteByHomeMain"));
    }

    @Test
    public void folderRestoreFirstTimeEndsAtUnlockGate() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void showRestoreSummaryDialog(");

        assertTrue("The folder-restore summary dialog's OK handler must complete"
                        + " via completeRestoreNavigation (which applies the"
                        + " first-time unlock gate), not by navigating directly.",
                body.contains("completeRestoreNavigation"));
        assertFalse("showRestoreSummaryDialog must not call"
                        + " onHomeWalletCompleteByHomeMain directly — direct"
                        + " navigation bypasses the first-time unlock gate.",
                body.contains("onHomeWalletCompleteByHomeMain"));
    }

    // ---------------------------------------------------------------
    // 7. Positive pin: the seed paths' contract stays intact
    // ---------------------------------------------------------------

    @Test
    public void seedPathsStillUseWalletPassword() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));

        String save = methodBody(src, "private void saveWalletFromSeedWords(");
        assertTrue("saveWalletFromSeedWords must keep bootstrapping the"
                        + " strongbox with the onboarding walletPassword — the"
                        + " create and restore-from-seed paths are the reference"
                        + " behaviour the restore paths are being aligned to.",
                Pattern.compile("createMainKey\\s*\\(\\s*getContext\\(\\)\\s*,\\s*walletPassword\\s*\\)")
                        .matcher(save).find());

        String finish = methodBody(src, "private void finishBackupAndNavigateToHome(");
        assertTrue("finishBackupAndNavigateToHome must keep routing through"
                        + " requirePasswordReentryThenNavigate; it is the unlock"
                        + " gate for the create and restore-from-seed paths.",
                finish.contains("requirePasswordReentryThenNavigate"));
    }

    // ---------------------------------------------------------------
    // 8. Positive pin: no strongbox change (existing-user compatibility)
    // ---------------------------------------------------------------

    @Test
    public void strongboxFormatUntouched() throws Exception {
        String coordinator = stripJavaComments(read(COORDINATOR_PATH));
        assertTrue("UnlockCoordinator.persist must keep re-using the on-disk"
                        + " passwordWrap envelope verbatim (no password rotation on"
                        + " write). This fix is flow-level only; existing users'"
                        + " strongboxes must decode byte-for-byte identically"
                        + " before and after.",
                Pattern.compile("passwordWrap\\s*=\\s*activeDecoded\\.passwordWrap")
                        .matcher(coordinator).find());

        // The only strongbox-creation surface is SecureStorage.createMainKey.
        // A fix that adds another createNewStrongbox call site would create a
        // second way to define the app password — exactly the bug class this
        // suite exists to prevent.
        List<String> offenders = new ArrayList<>();
        File root = locate(MAIN_JAVA_ROOT);
        scanForToken(root, ".createNewStrongbox(", offenders);
        assertEquals("Expected exactly one .createNewStrongbox( call site"
                        + " (SecureStorage.createMainKey). Offenders: " + offenders,
                1, offenders.size());
        assertTrue("The single .createNewStrongbox( call site must live in"
                        + " SecureStorage.java. Offenders: " + offenders,
                offenders.get(0).endsWith("SecureStorage.java"));
    }

    // ---------------------------------------------------------------
    // 9-11. The strongbox is created only when the FIRST WALLET is saved
    // ---------------------------------------------------------------
    //
    // Regression (found on a fresh install): creating the strongbox at
    // file-pick time -- before any wallet has been decrypted -- has two
    // consequences. (1) HomeActivity routes onboarding-vs-unlock purely on
    // isInitialized() ("a slot file exists") and SecureStorage has no
    // delete/reset, so quitting before the first wallet is saved leaves a
    // permanent EMPTY strongbox: the next launch opens a blank wallet with
    // no way back to onboarding. (2) UnlockCoordinator.createNewStrongbox
    // can throw AFTER the slot file is written, and a background create
    // with an empty onCancel fails silently -- the restore screen just
    // sits there. The contract, matching saveWalletFromSeedWords and iOS
    // createNewStrongboxWithInitialWallet: createMainKey immediately before
    // the first saveWallet, in the same task, and nowhere earlier.

    @Test
    public void restorePrepDoesNotCreateStrongbox() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void ensureStrongboxReadyForRestore(");

        assertFalse("ensureStrongboxReadyForRestore must NOT call createMainKey."
                        + " It runs at file/folder-pick time, before any wallet is"
                        + " decrypted; creating the strongbox there means a quit"
                        + " mid-restore leaves a permanent empty strongbox (next"
                        + " launch: blank wallet, no onboarding), and a failed"
                        + " background create dies silently. Record the password"
                        + " (pendingStrongboxPassword) and defer creation to the"
                        + " first saveWallet.",
                body.contains("createMainKey"));
    }

    @Test
    public void restoreUnlockDialogDoesNotCreateStrongbox() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void runStrongboxRestoreUnlock(");

        assertFalse("runStrongboxRestoreUnlock must NOT call createMainKey. On a"
                        + " fresh install its !isInitialized branch turned an"
                        + " \"Unlock\" dialog into a strongbox CREATE before any"
                        + " wallet was saved. Accept the password as"
                        + " pendingStrongboxPassword and let the first saveWallet"
                        + " create the strongbox.",
                body.contains("createMainKey"));
    }

    @Test
    public void folderRestoreCreatesStrongboxAtFirstSave() throws Exception {
        String src = stripJavaComments(read(FRAGMENT_PATH));
        String body = methodBody(src, "private void attemptBatchDecrypt(");

        int create = indexOfRegex(body,
                "createMainKey\\s*\\([\\s\\S]{0,60}?strongboxWritePassword");
        assertTrue("attemptBatchDecrypt must create the strongbox itself"
                        + " (createMainKey(..., strongboxWritePassword)) right"
                        + " before the first saveWallet, guarded by"
                        + " !isInitialized -- the folder path can no longer rely"
                        + " on the unlock dialog having created it earlier.",
                create >= 0);
        int guard = body.lastIndexOf("isInitialized", create);
        assertTrue("The createMainKey in attemptBatchDecrypt must be guarded by"
                        + " an isInitialized check (create once, never overwrite).",
                guard >= 0 && create - guard < 200);
        int save = body.indexOf("saveWallet(");
        assertTrue("saveWallet( call not found in attemptBatchDecrypt; the"
                + " batch-restore shape changed and this test needs updating"
                + " alongside it.", save >= 0);
        assertTrue("The strongbox must be created BEFORE the first saveWallet in"
                        + " attemptBatchDecrypt (create-then-save in one task, the"
                        + " same contract as saveWalletFromSeedWords).",
                create < save);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Three-way CWD fallback so the test works from both the module and the
     *  repo root (mirrors BackupRestoreLimiterRecordingTest). */
    private static File locate(String path) {
        File f = new File(path);
        if (!f.exists()) f = new File("app/" + path);
        if (!f.exists()) f = new File("../" + path);
        if (!f.exists()) {
            fail("could not locate " + path + " from working dir "
                    + new File(".").getAbsolutePath());
        }
        return f;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(locate(path).toPath()),
                StandardCharsets.UTF_8);
    }

    /** Strip line + block comments so rationale prose can never satisfy or
     *  trip a check. */
    private static String stripJavaComments(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)//[^\n]*", "");
    }

    /** Body of the method whose declaration starts with {@code signature},
     *  extracted by brace matching (a fixed-byte window is fragile; the
     *  brace walk survives edits inside the method). */
    private static String methodBody(String src, String signature) {
        int idx = src.indexOf(signature);
        assertTrue("method signature not found in source: " + signature, idx >= 0);
        return bodyAfter(src, signature);
    }

    /** Brace-matched block starting at the first '{' after {@code anchor}. */
    private static String bodyAfter(String src, String anchor) {
        int idx = src.indexOf(anchor);
        assertTrue("anchor not found in source: " + anchor, idx >= 0);
        int open = src.indexOf('{', idx);
        assertTrue("no opening brace after anchor: " + anchor, open >= 0);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        fail("unbalanced braces after anchor: " + anchor);
        return null; // unreachable
    }

    private static int indexOfRegex(String s, String regex) {
        java.util.regex.Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Recursive scan for a token across the production tree, comments
     *  stripped, reporting the files that contain it. */
    private static void scanForToken(File dir, String token, List<String> hits)
            throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                scanForToken(child, token, hits);
            } else if (child.getName().endsWith(".java")) {
                String s = stripJavaComments(new String(
                        Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8));
                if (s.contains(token)) hits.add(child.getPath());
            }
        }
    }
}

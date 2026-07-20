package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backup-restore brute-force-limiter wiring regression coverage.
 * The backup-restore code paths in
 * {@code HomeWalletFragment} historically read
 * {@link UnlockAttemptLimiter#currentDecision(android.content.Context)}
 * to gate a new attempt but never called {@code recordFailure(...)} or
 * {@code recordSuccess(...)} on the way out, so the documented
 * BACKUP_DECRYPT and STRONGBOX_UNLOCK channels were never incremented
 * — defeating the stair-step backoff for any attacker with the device
 * who could mount a brute-force loop through the restore dialog.
 *
 * <p>The fix wires four call sites:
 *
 * <ol>
 *   <li>{@code runStrongboxRestoreUnlock}: the dedicated pre-batch
 *       strongbox-unlock prompt (split from the per-file backup
 *       password by Item 10) records on STRONGBOX_UNLOCK — success
 *       when the user types the correct app password, failure when
 *       wrong.</li>
 *   <li>{@code attemptBatchDecrypt}: post-loop aggregate records on
 *       BACKUP_DECRYPT — success if any candidate decrypted, failure
 *       if zero of N candidates decrypted.</li>
 *   <li>{@code performRestoreFromUri}: catch block records on
 *       BACKUP_DECRYPT, success path records on BACKUP_DECRYPT.</li>
 * </ol>
 *
 * <p>This test asserts the wiring exists via source-text inspection.
 * The full behavioural test (Context + SharedPreferences + scrypt
 * roundtrip) belongs in androidTest; this JVM-only test catches the
 * regression class where the wiring is silently removed during a
 * future refactor.
 */
public class BackupRestoreLimiterRecordingTest {

    private static final String FRAGMENT_PATH =
            "app/src/main/java/com/quantumswap/app/view/fragment/"
                    + "HomeWalletFragment.java";

    @Test
    public void recordFailureWiredOnBootstrapUnlockFailure() throws Exception {
        String body = readFragment();

        // Item 10 split strongbox unlock from the per-file backup
        // password into a dedicated `runStrongboxRestoreUnlock` helper
        // that runs BEFORE the batch-decrypt loop. The helper must
        // still call recordFailure(STRONGBOX_UNLOCK) on the wrong-
        // password branch and recordSuccess on the correct branch.
        // Match both wirings inside the helper body.
        Pattern failurePattern = Pattern.compile(
                "runStrongboxRestoreUnlock\\b[\\s\\S]*?"
                        + "UnlockAttemptLimiter[\\s\\S]*?"
                        + "\\.recordFailure\\([\\s\\S]*?"
                        + "Channel\\.STRONGBOX_UNLOCK\\s*\\)",
                Pattern.MULTILINE);
        assertTrue(
                "Limiter-wiring regression: HomeWalletFragment "
                        + "runStrongboxRestoreUnlock must call "
                        + "UnlockAttemptLimiter.recordFailure(STRONGBOX_UNLOCK) "
                        + "on the wrong-app-password branch; the brute-force "
                        + "limiter is fully bypassed without it.",
                failurePattern.matcher(body).find());

        Pattern successPattern = Pattern.compile(
                "runStrongboxRestoreUnlock\\b[\\s\\S]*?"
                        + "UnlockAttemptLimiter[\\s\\S]*?"
                        + "\\.recordSuccess\\([\\s\\S]*?"
                        + "Channel\\.STRONGBOX_UNLOCK\\s*\\)",
                Pattern.MULTILINE);
        assertTrue(
                "Limiter-wiring regression: HomeWalletFragment "
                        + "runStrongboxRestoreUnlock must call "
                        + "UnlockAttemptLimiter.recordSuccess(STRONGBOX_UNLOCK) "
                        + "on the correct-password branch so the limiter is "
                        + "reset for the next legitimate user.",
                successPattern.matcher(body).find());
    }

    @Test
    public void recordSuccessOrFailureWiredAfterBatchPass() throws Exception {
        String body = readFragment();

        // After the while-iter loop we must record EITHER
        // recordSuccess(BACKUP_DECRYPT) (>=1 candidate decrypted) or
        // recordFailure(BACKUP_DECRYPT) (zero of N decrypted). Both
        // call shapes must be present in the same method body.
        assertTrue(
                "Limiter-wiring regression: HomeWalletFragment.attemptBatchDecrypt "
                        + "must call recordSuccess(BACKUP_DECRYPT) on "
                        + ">=1 successful decrypt.",
                Pattern.compile(
                        "recordSuccess\\([\\s\\S]*?Channel\\.BACKUP_DECRYPT")
                        .matcher(body).find());
        assertTrue(
                "Limiter-wiring regression: HomeWalletFragment.attemptBatchDecrypt "
                        + "must call recordFailure(BACKUP_DECRYPT) when zero "
                        + "of N candidates decrypted.",
                Pattern.compile(
                        "recordFailure\\([\\s\\S]*?Channel\\.BACKUP_DECRYPT")
                        .matcher(body).find());
    }

    @Test
    public void perCandidateSuccessCounterIncremented() throws Exception {
        String body = readFragment();

        // The post-loop aggregate decision needs a per-pass success
        // counter. Pin its initialization AND its increment so a
        // future refactor can't silently demote the post-loop call to
        // an unconditional recordSuccess.
        assertTrue(
                "Limiter-wiring regression: per-pass successful-decrypt counter "
                        + "missing — initialization must precede the "
                        + "while-iter loop.",
                Pattern.compile(
                        "int\\s+successfulDecryptsThisPass\\s*=\\s*0\\s*;")
                        .matcher(body).find());
        assertTrue(
                "Limiter-wiring regression: per-pass successful-decrypt counter "
                        + "is initialized but never incremented — every "
                        + "pass would falsely look like a failure.",
                Pattern.compile(
                        "successfulDecryptsThisPass\\s*\\+\\+\\s*;")
                        .matcher(body).find());
    }

    @Test
    public void recordFailureWiredInPerformRestoreFromUriCatch() throws Exception {
        String body = readFragment();

        // The single-file restore catch block (wrong-password is the
        // dominant failure mode) must record on BACKUP_DECRYPT before
        // re-enabling the password dialog, otherwise the dialog can
        // be retried unlimited times.
        Pattern p = Pattern.compile(
                "Restore decrypt failed[\\s\\S]*?"
                        + "UnlockAttemptLimiter[\\s\\S]*?"
                        + "\\.recordFailure\\([\\s\\S]*?"
                        + "Channel\\.BACKUP_DECRYPT\\s*\\)",
                Pattern.MULTILINE);
        assertTrue(
                "Limiter-wiring regression: HomeWalletFragment.performRestoreFromUri "
                        + "catch block must call "
                        + "recordFailure(BACKUP_DECRYPT) before re-enabling "
                        + "the dialog.",
                p.matcher(body).find());
    }

    @Test
    public void totalLimiterCallSitesMatchExpected() throws Exception {
        String body = readFragment();

        // Sanity: exactly the call sites we expect — no more, no less.
        // If a future change adds another recordFailure or removes
        // one of the planned ones, this assertion forces a conscious
        // update of the test.
        int recordFailureCount = countMatches(body,
                Pattern.compile("UnlockAttemptLimiter[\\s\\S]{0,80}\\.recordFailure"));
        int recordSuccessCount = countMatches(body,
                Pattern.compile("UnlockAttemptLimiter[\\s\\S]{0,80}\\.recordSuccess"));
        // Expected after the cloud-restore strongbox-password-capture fix
        // (always-prompt with a verify-only branch when the strongbox is
        // already unlocked):
        //   4 recordFailure (runStrongboxRestoreUnlock unlock-path
        //                    STRONGBOX_UNLOCK,
        //                    runStrongboxRestoreUnlock verify-path
        //                    STRONGBOX_UNLOCK,
        //                    post-loop BACKUP_DECRYPT,
        //                    performRestoreFromUri BACKUP_DECRYPT)
        //   5 recordSuccess (runStrongboxRestoreUnlock createMainKey-path
        //                    STRONGBOX_UNLOCK,
        //                    runStrongboxRestoreUnlock verify-path
        //                    STRONGBOX_UNLOCK,
        //                    runStrongboxRestoreUnlock unlock-path
        //                    STRONGBOX_UNLOCK,
        //                    post-loop BACKUP_DECRYPT,
        //                    performRestoreFromUri BACKUP_DECRYPT)
        assertEquals(
                "Expected exactly 4 recordFailure call sites in "
                        + "HomeWalletFragment (runStrongboxRestoreUnlock "
                        + "unlock-path STRONGBOX_UNLOCK, "
                        + "runStrongboxRestoreUnlock verify-path "
                        + "STRONGBOX_UNLOCK, post-loop BACKUP_DECRYPT, "
                        + "performRestoreFromUri BACKUP_DECRYPT). Found "
                        + recordFailureCount + ".",
                4, recordFailureCount);
        assertEquals(
                "Expected exactly 5 recordSuccess call sites in "
                        + "HomeWalletFragment (runStrongboxRestoreUnlock "
                        + "createMainKey-path STRONGBOX_UNLOCK, "
                        + "runStrongboxRestoreUnlock verify-path "
                        + "STRONGBOX_UNLOCK, runStrongboxRestoreUnlock "
                        + "unlock-path STRONGBOX_UNLOCK, post-loop "
                        + "BACKUP_DECRYPT, performRestoreFromUri "
                        + "BACKUP_DECRYPT). Found "
                        + recordSuccessCount + ".",
                5, recordSuccessCount);
    }

    private static int countMatches(String haystack, Pattern p) {
        Matcher m = p.matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String readFragment() throws Exception {
        File f = new File(FRAGMENT_PATH);
        if (!f.exists()) f = new File("../" + FRAGMENT_PATH);
        if (!f.exists()) f = new File(FRAGMENT_PATH.replaceFirst("^app/", ""));
        if (!f.exists()) {
            fail("could not locate " + FRAGMENT_PATH
                    + " from working dir " + new File(".").getAbsolutePath());
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}

package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wallet-creation whitespace-rejection regression coverage. The
 * wallet-creation password screen
 * ({@code HomeWalletFragment.homeSetWalletNextButton}) used to capture
 * the raw {@code EditText} value with no leading/trailing whitespace
 * check; the unlock path
 * ({@code HomeActivity.passwordEditText -> SecureStorage.unlock})
 * unconditionally trims, so a spaced password at create time silently
 * derived a different scrypt key than every subsequent unlock and
 * permanently locked the user out of their strongbox.
 *
 * <p>The fix adds a reject-on-whitespace guard mirroring
 * {@link com.quantumswap.app.view.dialog.BackupPasswordDialog}'s
 * {@code showCreateMode} (lines 234-239) and the iOS counterpart in
 * {@code HomeWalletViewController.swift}.
 *
 * <p>This JVM unit test asserts the three contracts that have to hold
 * for the guard to actually surface a user-visible error:
 *
 * <ol>
 *   <li>The {@code "passwordSpace"} key exists in
 *       {@code app/src/main/res/raw/en_us.json}.</li>
 *   <li>Its English wording matches the iOS
 *       {@code QuantumSwapWallet/Resources/en_us.json} byte-for-byte
 *       so a future translator cannot diverge the message.</li>
 *   <li>The accessor
 *       {@code JsonInteract#getPasswordSpaceByErrors()} exists with
 *       the expected signature so the call site in
 *       {@code HomeWalletFragment} compiles against a real method.</li>
 * </ol>
 */
public class WalletCreationPasswordWhitespaceTest {

    private static final String EXPECTED_MESSAGE =
            "Password cannot start or end with spaces";

    @Test
    public void passwordSpaceKeyPresentInAndroidEnUs() throws Exception {
        String body = readFile("app/src/main/res/raw/en_us.json");
        Pattern p = Pattern.compile(
                "\"passwordSpace\"\\s*:\\s*\"([^\"\\\\]*)\"");
        Matcher m = p.matcher(body);
        assertTrue("Android en_us.json missing required key 'passwordSpace' "
                        + "needed for the whitespace-rejection guard",
                m.find());
        assertEquals(EXPECTED_MESSAGE, m.group(1));
    }

    @Test
    public void passwordSpaceWordingMatchesIosByteForByte() throws Exception {
        // iOS path is relative to the sibling iOS repo. The test only
        // runs the parity assertion if the iOS file is reachable from
        // the Android test runtime; otherwise it skips silently so CI
        // on Android-only checkouts does not fail.
        File iosFile = new File(
                "../quantum-coin-wallet-ios/QuantumSwapWallet/Resources/en_us.json");
        if (!iosFile.exists()) return;

        String iosBody = readFile(iosFile.getPath());
        Pattern p = Pattern.compile(
                "\"passwordSpace\"\\s*:\\s*\"([^\"\\\\]*)\"");
        Matcher m = p.matcher(iosBody);
        assertTrue("iOS en_us.json missing 'passwordSpace' key", m.find());
        String iosWording = m.group(1);
        assertEquals("Android and iOS 'passwordSpace' wording must match"
                        + " byte-for-byte (cross-platform UX parity).",
                EXPECTED_MESSAGE, iosWording);
    }

    @Test
    public void jsonInteractHasPasswordSpaceAccessor() throws Exception {
        Class<?> ji = Class.forName(
                "com.quantumswap.app.interact.JsonInteract");
        Method m = ji.getDeclaredMethod("getPasswordSpaceByErrors");
        assertNotNull(m);
        assertEquals(String.class, m.getReturnType());
    }

    @Test
    public void jsonViewModelHasPasswordSpaceAccessor() throws Exception {
        // Call sites in production code reach for the key via the
        // ViewModel layer (HomeWalletFragment, BackupPasswordDialog).
        // If the accessor is renamed or removed, the call site silently
        // falls back to whatever default the helper provides — which
        // for HomeWalletFragment is a null-message dialog. Pin it.
        Class<?> vm = Class.forName(
                "com.quantumswap.app.viewmodel.JsonViewModel");
        Method m = vm.getDeclaredMethod("getPasswordSpaceByErrors");
        assertNotNull(m);
        assertEquals(String.class, m.getReturnType());
    }

    private static String readFile(String relativePath) throws Exception {
        File f = new File(relativePath);
        if (!f.exists()) {
            // Some Gradle invocations set the working dir to the
            // module dir (./app), others to the project root. Try
            // both before failing so the test is location-agnostic.
            f = new File("../" + relativePath);
        }
        if (!f.exists()) {
            f = new File(relativePath.replaceFirst("^app/", ""));
        }
        assertTrue("could not locate " + relativePath
                + " from working dir " + new File(".").getAbsolutePath(),
                f.exists());
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}

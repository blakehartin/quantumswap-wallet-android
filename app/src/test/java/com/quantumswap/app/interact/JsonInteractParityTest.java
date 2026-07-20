package com.quantumswap.app.interact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2 parity test (Android, mirrors iOS
 * {@code QuantumSwapWalletTests/LocalizationTests.swift}).
 *
 * <p>Loads {@code app/src/main/res/raw/en_us.json} directly via the
 * filesystem and asserts:
 *
 * <ul>
 *   <li>Every Stage 2 key is present in the file (catches a missing-
 *       key regression at commit time, not at first render).</li>
 *   <li>Every Stage 2 key has a matching {@code get*ByLangValues()}
 *       method on {@link JsonInteract} (catches a missing-accessor
 *       regression so call sites cannot reach for a key that the
 *       interact layer cannot serve).</li>
 *   <li>The 5 Stage 1 realignments (§O.5) hold their iOS-canonical
 *       wording -- a regression here would silently revert the user-
 *       visible text on the Send / Networks / Settings screens.</li>
 *   <li>The OS-specific divergences allow-listed in plan §O.4 (root vs
 *       jailbreak, Play Store vs App Store) actually use the Android
 *       wording -- catches a careless verbatim resync from iOS.</li>
 * </ul>
 *
 * <p>The test deliberately does NOT spin up
 * a full {@link JsonInteract} parser. Android's stubbed
 * {@code org.json} on the JVM unit-test classpath throws at runtime
 * unless we depend on a real {@code org.json}, which would expand the
 * Gradle verification-metadata footprint for one test. Reading the
 * file directly + reflection-checking accessor existence is enough
 * to catch the regression class this suite exists for.
 */
public class JsonInteractParityTest {

    /**
     * The 27 keys ported from iOS in Stage 2. Update this set
     * AND the §O.5 lists below when adding more keys; the helper
     * {@link #assertHasAccessor(String)} keeps the accessor surface
     * in lockstep with this list.
     */
    private static final String[] STAGE_2_KEYS = new String[] {
            "address-checksum-warning",
            "backup-encrypted-warning",
            "block-explorer-title",
            "decimals",
            "decrypting-wallet",
            "help",
            "no-active-network",
            "seed-accessibility-summary",
            "seed-hidden-for-capture",
            "status-verifying",
            "strongbox-degraded-banner",
            "submitting-transaction",
            "tamper-continue-at-risk",
            "tamper-debugger-banner",
            "tamper-debugger-message",
            "tamper-debugger-title",
            "tamper-ignore-and-resume",
            "tamper-jailbreak-banner",
            "tamper-jailbreak-message",
            "tamper-jailbreak-title",
            "tamper-quit",
            "tamper-runtime-banner",
            "tamper-runtime-message",
            "tamper-runtime-title",
            "transaction-message-exits",
            "transaction-sent",
            "wait-opening-picker",
    };

    /**
     * Mapping from JSON key to expected JsonInteract accessor method
     * name. Only the methods whose names don't follow the trivial
     * camel-case rule need explicit listing; the rest are derived in
     * {@link #defaultAccessorName(String)}.
     */
    private static final Map<String, String> EXPLICIT_ACCESSORS = new HashMap<>();
    static {
        // The §O.5 realignment keys live at non-derivable accessor
        // method names; explicitly map them so the parity test catches
        // a renamed accessor.
        EXPLICIT_ACCESSORS.put("dpscan", "getDpscanByLangValues");
        EXPLICIT_ACCESSORS.put("id", "getIdByLangValues");
        EXPLICIT_ACCESSORS.put("address-to-send", "getAddressToSendByLangValues");
        EXPLICIT_ACCESSORS.put("quantity-to-send", "getQuantityToSendByLangValues");
        EXPLICIT_ACCESSORS.put("advanced-signing-option", "getAdvancedSigningOptionByLangValues");
        EXPLICIT_ACCESSORS.put("set-wallet-passowrd", "getSetWalletPasswordByLangValues");
        EXPLICIT_ACCESSORS.put("cloud-backup-info", "getCloudBackupInfoByLangValues");
        EXPLICIT_ACCESSORS.put("transaction-id", "getTransactionIdByLangValues");
    }

    private Map<String, String> langValues;
    private Set<String> declaredAccessors;

    @Before
    public void setUp() throws Exception {
        File f = new File("src/main/res/raw/en_us.json");
        if (!f.exists()) f = new File("app/src/main/res/raw/en_us.json");
        if (!f.exists()) fail("Could not locate en_us.json from " + new File(".").getAbsolutePath());
        langValues = parseLangValues(f);

        declaredAccessors = new HashSet<>();
        for (Method m : JsonInteract.class.getDeclaredMethods()) {
            if (m.getName().endsWith("ByLangValues")) declaredAccessors.add(m.getName());
        }
    }

    @Test
    public void preservedTypoKey_setWalletPasswordResolves() {
        // Mirrors iOS LocalizationTests.testPreservedTypoKeyResolves.
        assertHasKey("set-wallet-passowrd");
        assertHasAccessor("set-wallet-passowrd");
    }

    @Test
    public void cloudBackupInfo_resolves() {
        // Mirrors iOS LocalizationTests.testCloudBackupInfoResolves.
        assertHasKey("cloud-backup-info");
        assertHasAccessor("cloud-backup-info");
    }

    @Test
    public void partOStage2_allKeysPresentInJson() {
        for (String key : STAGE_2_KEYS) {
            assertHasKey(key);
        }
    }

    @Test
    public void partOStage2_allKeysHaveJsonInteractAccessors() {
        for (String key : STAGE_2_KEYS) {
            assertHasAccessor(key);
        }
    }

    @Test
    public void partOStage1_realignedValuesMatchIosCanonical() {
        // §O.5 realignments. A regression here means the realigned
        // value silently reverted; compare against the values
        // listed in plan §O.5.
        assertEquals("Block Explorer", langValues.get("dpscan"));
        assertEquals("Network ID", langValues.get("id"));
        assertEquals("To address", langValues.get("address-to-send"));
        assertEquals("Quantity", langValues.get("quantity-to-send"));
        assertEquals("Advanced signing", langValues.get("advanced-signing-option"));
        assertEquals("Transaction ID", langValues.get("transaction-id"));
    }

    @Test
    public void osSpecificDivergence_jailbreakUsesRootWording() {
        // Plan §O.4 allow-lists this divergence. A verbatim resync
        // from iOS would surface "jailbroken" on a rooted Android
        // device, which is wrong wording.
        assertContains("tamper-jailbreak-banner", "Rooted");
        assertContains("tamper-jailbreak-message", "rooted");
        assertContains("tamper-jailbreak-title", "Reduced device protection");
    }

    @Test
    public void osSpecificDivergence_runtimePointsToPlayStore() {
        // Same allow-list: reinstall destination is Play Store.
        assertContains("tamper-runtime-message", "Play Store");
    }

    @Test
    public void osSpecificDivergence_backupWordingIsAndroid() {
        // backup-description, backup-encrypted-warning, and the cloud
        // copy refer to Android Auto Backup / generic cloud rather
        // than iCloud / Finder.
        assertContains("backup-description", "phone backups");
        assertContains("backup-encrypted-warning", "Android Auto Backup");
        assertContains("cloud-backup-info", "cloud");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private void assertHasKey(String key) {
        assertNotNull("expected key '" + key + "' missing from en_us.json langValues",
                langValues.get(key));
        assertFalse("expected key '" + key + "' is empty in en_us.json",
                langValues.get(key).isEmpty());
    }

    private void assertHasAccessor(String key) {
        String accessor = EXPLICIT_ACCESSORS.containsKey(key)
                ? EXPLICIT_ACCESSORS.get(key)
                : defaultAccessorName(key);
        assertTrue("JsonInteract accessor '" + accessor +
                        "' missing for key '" + key + "'",
                declaredAccessors.contains(accessor));
    }

    private void assertContains(String key, String needle) {
        String haystack = langValues.get(key);
        assertNotNull("missing key " + key, haystack);
        if (!haystack.contains(needle)) {
            fail("key '" + key + "' expected to contain '" + needle + "' but was: " + haystack);
        }
    }

    /** kebab-case key -> camelCase accessor: "tamper-debugger-banner" -> "getTamperDebuggerBannerByLangValues". */
    private static String defaultAccessorName(String key) {
        StringBuilder sb = new StringBuilder("get");
        for (String part : key.split("-")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        sb.append("ByLangValues");
        return sb.toString();
    }

    /**
     * Hand-rolled extractor that pulls every {@code "key": "value"}
     * pair from the {@code langValues} object in en_us.json. Avoids
     * depending on the stubbed {@code org.json} on the unit-test
     * classpath. Tolerates escaped quotes and backslashes inside
     * value strings.
     */
    private static Map<String, String> parseLangValues(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        String src = sb.toString();
        int idx = src.indexOf("\"langValues\"");
        if (idx < 0) throw new IllegalStateException("langValues block not found");
        // Find the opening { after "langValues":
        int openBrace = src.indexOf('{', idx);
        if (openBrace < 0) throw new IllegalStateException("langValues opening brace not found");
        // Walk the brace depth to find the matching close.
        int depth = 0;
        int close = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = openBrace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) throw new IllegalStateException("unterminated langValues block");
        String body = src.substring(openBrace + 1, close);

        Map<String, String> out = new HashMap<>();
        // Match: "key": "value" -- value handles escaped quotes.
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(body);
        while (m.find()) {
            String key = m.group(1);
            String rawValue = m.group(2);
            // Unescape the common JSON escapes we actually use.
            String value = rawValue
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
            out.put(key, value);
        }
        return out;
    }
}

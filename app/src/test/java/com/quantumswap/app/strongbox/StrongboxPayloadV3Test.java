package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure-JVM regression coverage of the v=3 unified-schema
 * {@link StrongboxPayload}.
 *
 * <p>Pins down the on-disk-shape and crypto-binding invariants
 * that the cross-platform vector suite under
 * {@code tests/fixtures/strongbox-v3-vectors/} also enforces,
 * but at the per-method level so a regression surfaces as a
 * targeted failure rather than a single opaque end-to-end
 * mismatch. The expensive byte-for-byte vector comparisons
 * live in {@code StrongboxPortabilityVectorTest}; this class
 * is the everyday fast gate.</p>
 */
public class StrongboxPayloadV3Test {

    private static final byte[] FIXED_MAIN_KEY = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
    };

    @Test
    public void schemaVersion_isThree() {
        StrongboxPayload p = new StrongboxPayload();
        assertEquals("v=3 is the cross-platform-portable schema; "
                + "any regression to v=2 will silently break "
                + "Android <-> iOS slot-file portability",
                3, p.v);
        assertEquals(3, StrongboxPayload.SCHEMA_VERSION);
    }

    @Test
    public void checksumInfoLabel_isV3Specific() {
        // The HKDF info string MUST surface the schema version so
        // a v=3 mainKey can never collide with a v=2 derived key.
        // Both platforms pin the exact same string verbatim.
        assertEquals("strongbox-payload-checksum-v3",
                StrongboxPayload.CHECKSUM_INFO_LABEL);
    }

    @Test
    public void newPayload_hasV3FieldDefaults() {
        StrongboxPayload p = new StrongboxPayload();
        assertEquals(0, p.currentWalletIndex);
        assertEquals(0, p.activeNetworkIndex);
        // backupEnabled is intentionally NOT a payload field. The
        // OS-level backup-enabled toggle lives in
        // SharedPreferences (PrefConnect.BACKUP_ENABLED_KEY) so
        // the backup agent can read it pre-unlock.
        assertEquals("", p.cloudBackupFolderUri);
        assertFalse(p.advancedSigning);
        assertFalse(p.cameraPermissionAskedOnce);
        assertNotNull(p.wallets);
        assertNotNull(p.customNetworks);
        assertNotNull(p.secureItems);
        assertEquals("checksum starts empty until the first stamp",
                "", p.checksum);
    }

    @Test
    public void maxWalletIndex_emptyMapReturnsMinusOne() {
        StrongboxPayload p = new StrongboxPayload();
        assertEquals("Empty wallets map -> -1 sentinel so callers "
                + "can do +1 for the next wallet's slot",
                -1, p.maxWalletIndex());
    }

    @Test
    public void maxWalletIndex_returnsActualMaxAcrossUnsortedKeys() {
        StrongboxPayload p = new StrongboxPayload();
        p.wallets.put("0", "a");
        p.wallets.put("5", "b");
        p.wallets.put("2", "c");
        assertEquals("Must scan all keys, not just the last insert",
                5, p.maxWalletIndex());
    }

    @Test
    public void maxWalletIndex_ignoresNonNumericKeys() {
        StrongboxPayload p = new StrongboxPayload();
        p.wallets.put("0", "a");
        p.wallets.put("not-a-number", "b");
        p.wallets.put("3", "c");
        assertEquals("Defensive parse: stale or malformed keys must "
                + "not poison the index calculation",
                3, p.maxWalletIndex());
    }

    @Test
    public void canonicalBytesForChecksum_isStableUnderRepeatedCalls() {
        StrongboxPayload p = makeSamplePayload();
        byte[] a = p.canonicalBytesForChecksum();
        byte[] b = p.canonicalBytesForChecksum();
        assertArrayEquals("canonicalBytesForChecksum MUST be a pure "
                + "function of payload state — any nondeterminism "
                + "would break the cross-platform inner-checksum",
                a, b);
    }

    @Test
    public void canonicalBytesForChecksum_omitsChecksumField() {
        StrongboxPayload p = makeSamplePayload();
        byte[] before = p.canonicalBytesForChecksum();
        p.checksum = "totally-different-checksum-value";
        byte[] after = p.canonicalBytesForChecksum();
        assertArrayEquals("Mutating checksum MUST NOT change the "
                + "canonical bytes used to compute the checksum, "
                + "or the keyed-HMAC chain is circular",
                before, after);
    }

    @Test
    public void stampChecksum_thenVerifyChecksum_succeeds() {
        StrongboxPayload p = makeSamplePayload();
        p.stampChecksum(FIXED_MAIN_KEY);
        assertTrue("Freshly-stamped payload must verify under the "
                + "same mainKey", p.verifyChecksum(FIXED_MAIN_KEY));
    }

    @Test
    public void verifyChecksum_failsUnderDifferentMainKey() {
        StrongboxPayload p = makeSamplePayload();
        p.stampChecksum(FIXED_MAIN_KEY);
        byte[] otherKey = FIXED_MAIN_KEY.clone();
        otherKey[0] ^= 0x01;
        assertFalse("A different mainKey MUST NOT verify — that is "
                + "the whole point of switching from unkeyed SHA-256 "
                + "to a keyed HMAC under v=3",
                p.verifyChecksum(otherKey));
    }

    @Test
    public void verifyChecksum_failsAfterAnyMutation() {
        StrongboxPayload p = makeSamplePayload();
        p.stampChecksum(FIXED_MAIN_KEY);
        // Flip a tracked field to invalidate the canonical bytes.
        p.activeNetworkIndex = p.activeNetworkIndex + 1;
        assertFalse("Any mutation to a checksummed field must "
                + "fail verifyChecksum on next read",
                p.verifyChecksum(FIXED_MAIN_KEY));
    }

    @Test
    public void encodeForSeal_includesStampedChecksumInOutput() {
        StrongboxPayload p = makeSamplePayload();
        byte[] encoded = p.encodeForSeal(FIXED_MAIN_KEY);
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertTrue("encodeForSeal output must include the checksum "
                + "field for the on-disk reader to verify against",
                json.contains("\"checksum\":\""));
        assertFalse("Checksum stamp must be a non-empty string",
                json.contains("\"checksum\":\"\""));
    }

    @Test
    public void decode_thenStampThenVerify_roundTrips() {
        StrongboxPayload original = makeSamplePayload();
        original.secureItems.put("k1", "opaque-value-1");
        byte[] encoded = original.encodeForSeal(FIXED_MAIN_KEY);
        StrongboxPayload restored = StrongboxPayload.decode(encoded);
        assertNotNull(restored);
        assertEquals(original.v, restored.v);
        assertEquals(original.currentWalletIndex, restored.currentWalletIndex);
        assertEquals(original.activeNetworkIndex, restored.activeNetworkIndex);
        assertEquals(original.advancedSigning, restored.advancedSigning);
        assertEquals(original.cloudBackupFolderUri, restored.cloudBackupFolderUri);
        assertEquals(original.cameraPermissionAskedOnce,
                restored.cameraPermissionAskedOnce);
        assertEquals("opaque-value-1", restored.secureItems.get("k1"));
        assertTrue("Restored payload's checksum must verify under "
                + "the same mainKey", restored.verifyChecksum(FIXED_MAIN_KEY));
    }

    @Test
    public void decode_legacyPlaintextWithMissingV3Fields_initializesDefaults() {
        // A future reader of an upgrade-in-progress payload (or a
        // forced v=3 file with a missing optional field) must not
        // NPE; defaults must paper over absent fields the same way
        // the iOS Decoder does.
        String minimal = "{\"v\":3,\"wallets\":{},\"checksum\":\"\"}";
        StrongboxPayload p = StrongboxPayload.decode(
                minimal.getBytes(StandardCharsets.UTF_8));
        assertNotNull(p);
        assertNotNull(p.customNetworks);
        assertNotNull(p.secureItems);
        assertEquals("", p.cloudBackupFolderUri);
    }

    private static StrongboxPayload makeSamplePayload() {
        StrongboxPayload p = new StrongboxPayload();
        p.wallets.put("0", "opaque-wallet-blob-0");
        p.wallets.put("1", "opaque-wallet-blob-1");
        p.currentWalletIndex = 1;
        StrongboxPayload.Network n = new StrongboxPayload.Network();
        n.name = "test-net";
        n.chainId = "424242";
        n.scanApiDomain = "scan.example";
        n.rpcEndpoint = "https://rpc.example";
        n.blockExplorerUrl = "https://explorer.example";
        p.customNetworks.add(n);
        p.activeNetworkIndex = 1;
        // backupEnabled is intentionally not set: it is a
        // SharedPreferences pref (PrefConnect.BACKUP_ENABLED_KEY),
        // not a payload field.
        p.cloudBackupFolderUri = "content://example/folder";
        p.advancedSigning = false;
        p.cameraPermissionAskedOnce = true;
        return p;
    }

    @Test
    public void canonicalBytes_omitBackupEnabled() {
        // The `backupEnabled` field is intentionally absent from
        // the v=3 schema. A regression that re-adds it would
        // silently re-introduce a parity gap with iOS.
        StrongboxPayload p = makeSamplePayload();
        String canonical = new String(p.canonicalBytesForChecksum(),
                StandardCharsets.UTF_8);
        assertFalse("backupEnabled MUST NOT appear in canonical JSON: "
                + canonical,
                canonical.contains("backupEnabled"));
        // And it must round-trip without ever setting that key:
        byte[] encoded = p.encodeForSeal(FIXED_MAIN_KEY);
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertFalse("backupEnabled MUST NOT appear in encoded payload: "
                + json,
                json.contains("backupEnabled"));
    }

    @Test
    public void mainKey_arrayIsNotZeroizedByOurApi() {
        // Defensive: payload.computeChecksum/stampChecksum must not
        // zeroize the caller-provided mainKey buffer. The persist
        // path zeros mainKey itself in a finally block; payload
        // must leave it alone or we'll double-zero a buffer the
        // caller still needs (e.g. for HKDF integrity-key
        // derivation right after).
        byte[] keyCopy = FIXED_MAIN_KEY.clone();
        StrongboxPayload p = makeSamplePayload();
        p.stampChecksum(keyCopy);
        assertArrayEquals("payload.stampChecksum must not mutate "
                + "the caller's mainKey buffer",
                FIXED_MAIN_KEY, keyCopy);
        p.verifyChecksum(keyCopy);
        assertArrayEquals("payload.verifyChecksum must not mutate "
                + "the caller's mainKey buffer",
                FIXED_MAIN_KEY, keyCopy);
        assertFalse("sanity: shipped fixture key is not all-zero",
                Arrays.equals(new byte[FIXED_MAIN_KEY.length], keyCopy));
    }
}

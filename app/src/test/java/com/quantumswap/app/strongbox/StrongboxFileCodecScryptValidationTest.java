package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Regression coverage for
 * {@link StrongboxFileCodec#validateScryptParams(int, int, int, int)}.
 *
 * <p>Without this guard, an attacker who could place a slot file
 * (e.g., via Auto-Backup restore) plus the user's known password
 * could craft a valid v=3 envelope with {@code N=1024, r=1, p=1};
 * the slot's MAC verifies under those weakened params (because
 * the MAC key is HKDF(mainKey, salt, "integrity-v2") and
 * {@code mainKey} is the scrypt output), so the only thing
 * standing between a brute-forceable slot and unlock is this
 * min-bound check.</p>
 *
 * <p>The codec parses the on-disk envelope through
 * {@code org.json.JSONObject}, which on the unit-test JVM
 * classpath is stubbed by Android (every method throws "not
 * mocked"). Routing through the public
 * {@link StrongboxFileCodec#validateScryptParams} helper lets us
 * exercise the bound check directly without paying the cost of
 * building a full slot envelope at test time, while still
 * sharing the exact production code path that
 * {@code StrongboxFileCodec.decodeOnly} executes.</p>
 *
 * <p>Mirrored on iOS by the symmetric tests in
 * {@code StrongboxLayerTests.swift}.</p>
 */
public class StrongboxFileCodecScryptValidationTest {

    @Test
    public void validateScryptParams_acceptsDocumentedDefaults() throws Exception {
        StrongboxFileCodec.validateScryptParams(
                StrongboxFileCodec.DEFAULT_SCRYPT_N,
                StrongboxFileCodec.DEFAULT_SCRYPT_R,
                StrongboxFileCodec.DEFAULT_SCRYPT_P,
                StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN);
    }

    @Test
    public void validateScryptParams_acceptsAboveDefault() throws Exception {
        StrongboxFileCodec.validateScryptParams(
                StrongboxFileCodec.DEFAULT_SCRYPT_N * 2,
                StrongboxFileCodec.DEFAULT_SCRYPT_R + 1,
                StrongboxFileCodec.DEFAULT_SCRYPT_P + 1,
                StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN + 1);
    }

    @Test
    public void validateScryptParams_rejectsLowN() {
        assertRejectedAsMalformed(
                1024,
                StrongboxFileCodec.DEFAULT_SCRYPT_R,
                StrongboxFileCodec.DEFAULT_SCRYPT_P,
                StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN,
                "N=1024 must be rejected (well below 262144 floor)");
    }

    @Test
    public void validateScryptParams_rejectsLowR() {
        assertRejectedAsMalformed(
                StrongboxFileCodec.DEFAULT_SCRYPT_N,
                1,
                StrongboxFileCodec.DEFAULT_SCRYPT_P,
                StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN,
                "r=1 must be rejected (block-mix bound)");
    }

    @Test
    public void validateScryptParams_rejectsLowKeyLen() {
        assertRejectedAsMalformed(
                StrongboxFileCodec.DEFAULT_SCRYPT_N,
                StrongboxFileCodec.DEFAULT_SCRYPT_R,
                StrongboxFileCodec.DEFAULT_SCRYPT_P,
                16,
                "keyLen=16 must be rejected (we mandate 32 bytes for AES-256-GCM keying)");
    }

    @Test
    public void validateScryptParams_rejectsZeroP() {
        assertRejectedAsMalformed(
                StrongboxFileCodec.DEFAULT_SCRYPT_N,
                StrongboxFileCodec.DEFAULT_SCRYPT_R,
                0,
                StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN,
                "p=0 must be rejected (no parallelism is degenerate)");
    }

    @Test
    public void validateScryptParams_rejectsNegativeAsMissingField() {
        try {
            StrongboxFileCodec.validateScryptParams(-1, 8, 1, 32);
            fail("negative N must be rejected");
        } catch (StrongboxFileCodec.CodecException ce) {
            assertEquals(
                    "negative parameter is reported as the historical "
                            + "MISSING_FIELD shape so existing callers stay "
                            + "byte-compatible with the previous error code",
                    StrongboxFileCodec.ErrorKind.MISSING_FIELD,
                    ce.kind);
        }
    }

    @Test
    public void kdfParams_publicCtorAcceptsArbitraryValues() {
        StrongboxFileCodec.KdfParams weak =
                new StrongboxFileCodec.KdfParams(1024, 1, 1, 16);
        assertEquals(1024, weak.N);
        assertEquals(1, weak.r);
        assertEquals(1, weak.p);
        assertEquals(16, weak.keyLen);
    }

    private static void assertRejectedAsMalformed(
            int N, int r, int p, int keyLen, String why) {
        try {
            StrongboxFileCodec.validateScryptParams(N, r, p, keyLen);
            fail(why + " — validateScryptParams returned without throwing");
        } catch (StrongboxFileCodec.CodecException ce) {
            assertEquals("must be MALFORMED_JSON not MISSING_FIELD",
                    StrongboxFileCodec.ErrorKind.MALFORMED_JSON, ce.kind);
            assertTrue("error message should mention the documented minimum: "
                            + ce.getMessage(),
                    ce.getMessage().contains("below documented minimum"));
        }
    }
}

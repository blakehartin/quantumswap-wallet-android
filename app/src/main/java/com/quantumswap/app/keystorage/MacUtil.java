package com.quantumswap.app.keystorage;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA-256 and HKDF-SHA-256 helpers used by the strongbox
 * file-level MAC and key-derivation paths.
 * <p>The class is named {@code MacUtil} (not {@code Mac}) so it
 * does not collide with {@link javax.crypto.Mac} when both are
 * imported in the same file.</p>
 * - HMAC-SHA-256 per RFC 2104 / RFC 4231 via JCE
 *   {@link javax.crypto.Mac} (test vectors in
 *   {@code MacKatTest.java}).
 * - HKDF-SHA-256 per RFC 5869 via BouncyCastle
 *   {@link HKDFBytesGenerator}, two-step extract-then-expand.
 *   BouncyCastle is FIPS 140-2 certified and widely audited,
 *   eliminating the need for hand-rolled Extract+Expand loops.
 *   The {@code info} string for the strongbox file MAC key is the
 *   ASCII bytes of {@value #INTEGRITY_INFO_LABEL} which provides
 *   domain separation per RFC 5869 §3.2: the same {@code mainKey}
 *   used for AEAD seal/open of the strongbox payload yields a
 *   different MAC key because the {@code info} byte string
 *   differs.
 * - Constant-time comparison via {@link MessageDigest#isEqual}
 *   (Java spec guarantees this is not short-circuit). Mirrors
 *   iOS {@code HMAC&lt;SHA256&gt;.isValidAuthenticationCode}.</p>
 * <p><b>(android-ios parity):</b>
 * The HKDF info string MUST be exactly {@value #INTEGRITY_INFO_LABEL}
 * (no trailing null, no whitespace). iOS source-of-truth:
 * {@code QuantumSwapWallet/Schema/StrongboxFileCodec.swift}
 * {@code macInfoLabel}. Both platforms produce byte-exact HKDF
 * output (iOS via CryptoKit, Android via BouncyCastle), verified
 * by {@code StrongboxPortabilityVectorTest}.</p>
 */
public final class MacUtil {

    /** RFC 5869 {@code info} for the strongbox file-level MAC key. */
    public static final String INTEGRITY_INFO_LABEL = "integrity-v2";

    /** SHA-256 output length in bytes. */
    public static final int HMAC_LEN = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private MacUtil() {}

    /** HMAC-SHA-256 of {@code message} under {@code key}.
     *  Returns a fresh 32-byte array. */
    public static byte[] hmacSha256(byte[] message, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (Exception e) {
            // HmacSHA256 is mandatory in every JCE provider per
            // android.security.spec.* contracts; this branch is
            // unreachable on any supported runtime.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

     /**
     * Constant-time verify of {@code candidateTag} against
     * {@code HMAC(message, key)}. Returns {@code false} for any
     * length mismatch or byte mismatch.
     * <p>the length-mismatch check
     * is a fast-path that does NOT leak timing about the secret
     * because the candidate length is attacker-supplied and the
     * key length is fixed. Only the byte compare needs to be
     * constant-time — which it is via {@link MessageDigest#isEqual}.
     * </p>
     */
    public static boolean verify(byte[] message, byte[] candidateTag, byte[] key) {
        if (candidateTag == null || candidateTag.length != HMAC_LEN) {
            return false;
        }
        byte[] computed = hmacSha256(message, key);
        return MessageDigest.isEqual(computed, candidateTag);
    }

     /**
     * RFC 5869 HKDF-SHA-256 extract-then-expand via BouncyCastle.
     * Replaces the previous hand-rolled Extract+Expand loop with
     * a well-vetted, FIPS-certified implementation.
     * BouncyCastle's {@link HKDFBytesGenerator} implements RFC 5869
     * Extract+Expand identically to the previous hand-rolled version,
     * ensuring byte-for-byte backward compatibility with existing
     * strongbox files. The {@code HKDFParameters} constructor
     * performs Extract internally, and {@code generateBytes(...)}
     * performs Expand. iOS uses CryptoKit's native
     * {@code HKDF<SHA256>.deriveKey(...)} which also implements
     * RFC 5869, so the cross-platform portability contract
     * (tested by {@code StrongboxPortabilityVectorTest}) is
     * preserved.</p>
     * @param ikm   input keying material
     * @param salt  salt (may be empty; RFC 5869 §2.2 then uses zeros)
     * @param info  domain-separation context
     * @param outLen output length; max RFC limit 255 * 32 = 8160 bytes
     */
    public static byte[] hkdfExtractAndExpand(byte[] ikm, byte[] salt, String info, int outLen) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HMAC_LEN] : salt;
        byte[] infoBytes = info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8);
        
        if (outLen <= 0 || outLen > 255 * HMAC_LEN) {
            throw new IllegalArgumentException("HKDF outLen out of range: " + outLen);
        }
        
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, effectiveSalt, infoBytes));
        
        byte[] out = new byte[outLen];
        hkdf.generateBytes(out, 0, outLen);
        return out;
    }
}

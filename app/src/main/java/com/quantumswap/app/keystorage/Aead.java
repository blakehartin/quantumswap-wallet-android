package com.quantumswap.app.keystorage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM seal / open with a versioned JSON envelope.
 * The wire envelope is JSON {@code {"v":2,"cipherText":"<b64(ct||tag)>","iv":"<b64,12B>"}}
 * — byte-for-byte identical to the iOS counterpart at
 * {@code QuantumSwapWallet/Crypto/Aead.swift}. Cross-platform
 * backups round-trip without re-encoding because both writers emit
 * the same bytes for the same logical input.</p>
 * Construction: AES-256-GCM with a fresh 12-byte IV per seal
 * (NIST SP 800-38D §8.2.2 nonce-uniqueness requirement met by
 * using {@link SecureRandom} which is at least 96 bits of
 * collision resistance per call), 128-bit auth tag. The combined
 * {@code cipherText} field stores ciphertext concatenated with
 * the 16-byte tag. {@code Cipher.doFinal} both encrypts and
 * appends the tag in a single call, so {@code cipherText.length
 * == plaintext.length + 16}. Open uses {@link AEADBadTagException}
 * for both wrong-key AND tampered-ciphertext outcomes — callers
 * cannot distinguish them, which is the intended uniform-failure
 * property and matches iOS {@code AeadError.authenticationFailed}.</p>
 * <p><b>(android-ios parity):</b>
 * The {@code "v":2} envelope MUST be byte-identical to iOS so
 * a wallet file written on one platform decrypts on the other.
 * Tests in {@code AeadCrossPlatformTest.java} assert this.</p>
 */
public final class Aead {

    /** On-wire schema marker. iOS and Android both write {@code 2}. */
    public static final int ENVELOPE_VERSION = 2;

    /** GCM nonce length in bytes. NIST SP 800-38D §5.2.1.1 recommends 12. */
    private static final int GCM_IV_BYTES = 12;

    /** GCM tag length in bits. 128-bit tag is the strongest standard option. */
    private static final int GCM_TAG_BITS = 128;

    /** Tag length in bytes; used for the {@code combined.length > 16} guard. */
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    /** Production RNG. The deterministic v=3 cross-platform
     *  vector tests pass a different {@link SecureRandomSource}
     *  to the seal-with-rng overload; this default is the only
     *  caller of the platform CSPRNG inside this class. */
    private static final SecureRandomSource defaultRandomSource =
            new DefaultSecureRandomSource();

    private Aead() {}

     /**
     * Errors surfaced by {@link #open(String, byte[])}. Callers
     * MUST treat {@link #AUTHENTICATION_FAILED} and
     * {@link #MALFORMED_ENVELOPE} as the same user-visible
     * outcome; the distinction is for log triage only.
     */
    public enum ErrorKind {
        /** Wrong key OR tampered ciphertext OR truncated tag. */
        AUTHENTICATION_FAILED,
        /** JSON shape wrong, missing fields, base64 decode fail,
         *  or the {@code combined.length <= 16} edge case where
         *  there is no room for a tag plus at least one ciphertext
         *  byte. */
        MALFORMED_ENVELOPE
    }

    /** Checked exception raised by {@link #open}. */
    public static final class AeadException extends GeneralSecurityException {
        public final ErrorKind kind;

        public AeadException(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public AeadException(ErrorKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

     /**
     * Seal {@code plaintext} under {@code key} and return the
     * canonical JSON envelope string. {@code key} MUST be 32
     * bytes (AES-256). A fresh random 12-byte IV is generated
     * for every call.
     * <p>the returned string is
     * the canonical wire format (sortedKeys = no extra
     * whitespace, alphabetical key order). Any future change
     * to the envelope shape MUST bump {@link #ENVELOPE_VERSION}
     * and add a migration shim to {@link #open}.</p>
     */
    public static String seal(byte[] plaintext, byte[] key) throws GeneralSecurityException {
        return seal(plaintext, key, defaultRandomSource);
    }

     /**
     * Seal under an injected {@link SecureRandomSource}. Used by
     * the v=3 cross-platform vector tests to produce
     * byte-deterministic AEAD envelopes from pinned IVs (see
     * {@code tests/fixtures/strongbox-v3-vectors/aead/}).
     * Production code MUST keep using {@link #seal(byte[], byte[])}
     * which routes to the platform CSPRNG.
     */
    public static String seal(byte[] plaintext, byte[] key, SecureRandomSource rng)
            throws GeneralSecurityException {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Aead.seal: key must be 32 bytes (AES-256)");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        rng.nextBytes(iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, KEY_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        byte[] combined = cipher.doFinal(plaintext);
        return canonicalize(
                Base64.getEncoder().encodeToString(combined),
                Base64.getEncoder().encodeToString(iv));
    }

     /**
     * Open the JSON envelope and return plaintext. Throws
     * {@link AeadException} with {@link ErrorKind#AUTHENTICATION_FAILED}
     * on wrong key or tampered ciphertext (uniform failure);
     * {@link ErrorKind#MALFORMED_ENVELOPE}
     * for shape problems that are not a key/tag issue.
     * <p>the {@code combined.length
     * &gt; 16} guard rejects the empty-ciphertext + tag edge
     * case BEFORE handing bytes to {@link Cipher#doFinal}. The
     * iOS counterpart uses the same guard. Without it, an
     * attacker who can supply a 16-byte combined value could
     * make {@link Cipher#doFinal} return a zero-length buffer
     * which a careless caller might treat as a successful
     * decrypt of an empty payload.</p>
     */
    public static byte[] open(String envelopeJson, byte[] key) throws AeadException {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Aead.open: key must be 32 bytes (AES-256)");
        }
        if (envelopeJson == null || envelopeJson.isEmpty()) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope is null or empty");
        }
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(envelopeJson);
            if (!parsed.isJsonObject()) {
                throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope is not a JSON object");
            }
            obj = parsed.getAsJsonObject();
        } catch (JsonSyntaxException je) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope is not valid JSON", je);
        }
        int v = obj.has("v") ? obj.get("v").getAsInt() : -1;
        if (v != ENVELOPE_VERSION) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope version " + v + " unsupported");
        }
        String ctB64 = obj.has("cipherText") ? obj.get("cipherText").getAsString() : null;
        String ivB64 = obj.has("iv") ? obj.get("iv").getAsString() : null;
        if (ctB64 == null || ivB64 == null) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope missing cipherText or iv");
        }
        byte[] iv;
        byte[] combined;
        try {
            iv = Base64.getDecoder().decode(ivB64);
            combined = Base64.getDecoder().decode(ctB64);
        } catch (IllegalArgumentException iae) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "envelope base64 decode failed", iae);
        }
        if (iv.length != GCM_IV_BYTES) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE,
                    "iv length " + iv.length + " != " + GCM_IV_BYTES);
        }
        if (combined.length <= GCM_TAG_BYTES) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE,
                    "combined ciphertext too short to contain a tag");
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, KEY_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            return cipher.doFinal(combined);
        } catch (AEADBadTagException badTag) {
            throw new AeadException(ErrorKind.AUTHENTICATION_FAILED, "AEAD tag verification failed", badTag);
        } catch (GeneralSecurityException gse) {
            throw new AeadException(ErrorKind.MALFORMED_ENVELOPE, "decrypt failed: " + gse.getMessage(), gse);
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

     /**
     * Canonical JSON encoding (sorted keys, no extra whitespace).
     * The Android JSONObject does not natively support sorted keys,
     * so we hand-build a canonical string for the small fixed shape
     * we emit. This mirrors iOS {@code JSONSerialization.data(...,
     * options: .sortedKeys)} byte-for-byte for the {@code {v,
     * cipherText, iv}} shape.
     */
    private static String canonicalize(String cipherTextB64, String ivB64) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        sb.append("\"cipherText\":\"").append(cipherTextB64).append("\",");
        sb.append("\"iv\":\"").append(ivB64).append("\",");
        sb.append("\"v\":").append(ENVELOPE_VERSION);
        sb.append('}');
        return sb.toString();
    }
}

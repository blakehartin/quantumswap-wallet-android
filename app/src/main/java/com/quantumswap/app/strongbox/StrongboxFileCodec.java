package com.quantumswap.app.strongbox;

import android.content.Context;

import com.quantumswap.app.keystorage.Aead;
import com.quantumswap.app.keystorage.MacUtil;
import com.quantumswap.app.storage.AtomicSlotWriter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * V3 strongbox file codec — the single source of truth for the
 * v3 slot-file JSON shape. Layer 2 of the storage stack.
 *
 * <p>v=3 is the cross-platform-portable schema; it differs from
 * the iOS-only v=2 in: (a) the inner {@code StrongboxPayload}
 * uses a unified field set that mirrors iOS exactly, (b) the
 * inner {@code checksum} is keyed by HKDF info
 * {@code "strongbox-payload-checksum-v3"}, (c) the scrypt salt
 * is 32 bytes (was 16 on iOS, was 32 on Android — converging on
 * 32). The on-disk file-level envelope (kdf / wrap / strongbox
 * / mac / uiBlockHash / ui) is unchanged byte-for-byte from v=2
 * except for the {@code v} field bump and the salt size.</p>
 *
 * <p><b>Why this exists (notes for reviewers):</b>
 * Every other layer either:
 * <ul>
 *   <li>Layer 1 sees only opaque bytes ({@link AtomicSlotWriter}).</li>
 *   <li>Layer 3 sees only AEAD primitives ({@link Aead}, {@link MacUtil}).</li>
 *   <li>Layer 4 ({@link com.quantumswap.app.keystorage.UnlockCoordinator})
 *       sees a typed decode result ({@link DecodedFile}) with
 *       fields like {@code salt}, {@code passwordWrap},
 *       {@code strongbox}, {@code mac} and never touches their
 *       JSON form.</li>
 *   <li>Layer 5 sees the post-MAC-verified, post-AEAD-opened,
 *       post-padding-stripped {@link StrongboxPayload}.</li>
 * </ul>
 * Concentrating the schema knowledge here means a future
 * schema bump (v2 -&gt; v3) only edits this file plus the
 * migration appendix.</p>
 *
 * <p><b>On-disk layout (canonical sortedKeys JSON):</b>
 * <pre>
 * {
 *   "v": 3,
 *   "generation": &lt;Int&gt;,
 *   "kdf": {
 *     "algorithm": "scrypt",
 *     "salt": "&lt;base64, 32 bytes&gt;",
 *     "params": { "N": 262144, "r": 8, "p": 1, "keyLen": 32 }
 *   },
 *   "wrap": {
 *     "passwordWrap": &lt;Aead envelope&gt;
 *   },
 *   "strongbox": &lt;Aead envelope of StrongboxPayload padded to 4 MiB&gt;,
 *   "uiBlockHash": "&lt;base64, 32 bytes&gt;",  // SHA-256 of canonical(ui)
 *   "mac": "&lt;base64, 32 bytes&gt;",
 *   "ui": { ...non-secret prefs... }
 * }
 * </pre>
 *
 * The MAC scope is exactly canonicalised JSON of
 * {@code {v, generation, kdf, wrap, strongbox, uiBlockHash}}.
 * The {@code ui} block ITSELF is not in the MAC scope (so a UI
 * pref change can be written without re-deriving the MAC key,
 * which would require the user's password); but the SHA-256 of
 * the canonical {@code ui} block IS in the MAC scope (via the
 * {@code uiBlockHash} field) so an attacker who swaps two slot
 * files' {@code ui} blocks — or replaces one slot's {@code ui}
 * block with attacker-chosen contents — cannot re-bind it
 * under the original MAC.</p>
 *
 * <p><b>Read algorithm:</b>
 * <ol>
 *   <li>{@link AtomicSlotWriter#cleanupTempFiles}.</li>
 *   <li>Read both slots. JSON-parse + schema-version each.</li>
 *   <li>Pre-MAC trial: AEAD-tag-check {@code passwordWrap} and
 *       {@code strongbox} on each parsed slot. Mark INVALID on
 *       tag fail.</li>
 *   <li>Pick winner = highest {@code generation} among VALID
 *       slots. One-valid-only path schedules an async
 *       re-mirror. Both-INVALID path -&gt; tamperDetected.</li>
 *   <li>Return the winner to layer 4 for password-unlock. The
 *       file-level MAC is verified inside layer 4 AFTER mainKey
 *       recovery (we cannot verify it pre-unlock because the
 *       MAC key is HKDF(mainKey, kdf.salt, "integrity-v2")).</li>
 * </ol></p>
 *
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code StrongboxFileCodec.swift} byte-for-byte.
 * Both platforms emit identical canonical JSON for the same
 * logical input. {@code CrossPlatformBackupTest.java} asserts
 * round-trip with iOS-pinned fixtures.</p>
 */
public final class StrongboxFileCodec {

    public static final int SCHEMA_VERSION = 3;

    /** AEAD envelope {@code alg} field is always exactly this
     *  string. Strict validation prevents the historical typo
     *  failure mode. */
    public static final String EXPECTED_AEAD_ALG = "AES-GCM";

    /** Default scrypt parameters. iOS and Android use the same
     *  defaults so the same password derives the same key on
     *  both platforms. */
    public static final int DEFAULT_SCRYPT_N = 262144;
    public static final int DEFAULT_SCRYPT_R = 8;
    public static final int DEFAULT_SCRYPT_P = 1;
    public static final int DEFAULT_SCRYPT_KEY_LEN = 32;

    public static final class CodecException extends GeneralSecurityException {
        public final ErrorKind kind;

        public CodecException(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    public enum ErrorKind {
        BOTH_SLOTS_INVALID,
        SCHEMA_VERSION_MISMATCH,
        MALFORMED_JSON,
        MISSING_FIELD,
        MAC_INVALID
    }

    /** Typed Aead envelope. Mirrors iOS
     *  {@code AeadEnvelope}. */
    public static final class AeadEnvelope {
        public final String alg;
        public final byte[] iv;
        public final byte[] ct;
        public final byte[] tag;

        public AeadEnvelope(String alg, byte[] iv, byte[] ct, byte[] tag) {
            this.alg = alg;
            this.iv = iv;
            this.ct = ct;
            this.tag = tag;
        }

        /** Render the legacy {@link Aead}-compatible JSON
         *  envelope so we can re-use {@link Aead#open} rather
         *  than re-implementing AES-GCM open at this layer. */
        public String legacyEnvelopeJson() {
            byte[] combined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, combined, 0, ct.length);
            System.arraycopy(tag, 0, combined, ct.length, tag.length);
            // canonical sortedKeys JSON, byte-identical to iOS
            StringBuilder sb = new StringBuilder(96);
            sb.append('{');
            sb.append("\"cipherText\":\"")
                    .append(b64Enc(combined))
                    .append("\",");
            sb.append("\"iv\":\"")
                    .append(b64Enc(iv))
                    .append("\",");
            sb.append("\"v\":").append(Aead.ENVELOPE_VERSION);
            sb.append('}');
            return sb.toString();
        }

        /** Build from the {@link Aead#seal} return string. */
        public static AeadEnvelope fromAeadSeal(String aeadJson)
                throws CodecException {
            try {
                JSONObject obj = new JSONObject(aeadJson);
                byte[] combined = b64Dec(obj.getString("cipherText"));
                byte[] iv = b64Dec(obj.getString("iv"));
                if (combined.length <= 16) {
                    throw new CodecException(ErrorKind.MALFORMED_JSON,
                            "AeadEnvelope.fromAeadSeal: combined too short");
                }
                byte[] ct = Arrays.copyOfRange(combined, 0, combined.length - 16);
                byte[] tag = Arrays.copyOfRange(combined, combined.length - 16,
                        combined.length);
                return new AeadEnvelope(EXPECTED_AEAD_ALG, iv, ct, tag);
            } catch (JSONException je) {
                throw new CodecException(ErrorKind.MALFORMED_JSON,
                        "AeadEnvelope.fromAeadSeal: " + je.getMessage());
            } catch (IllegalArgumentException iae) {
                throw new CodecException(ErrorKind.MALFORMED_JSON,
                        "AeadEnvelope.fromAeadSeal: base64 decode failed: " + iae.getMessage());
            }
        }
    }

    public static final class KdfParams {
        public final int N;
        public final int r;
        public final int p;
        public final int keyLen;

        public KdfParams(int N, int r, int p, int keyLen) {
            this.N = N;
            this.r = r;
            this.p = p;
            this.keyLen = keyLen;
        }

        public static KdfParams defaults() {
            return new KdfParams(DEFAULT_SCRYPT_N, DEFAULT_SCRYPT_R,
                    DEFAULT_SCRYPT_P, DEFAULT_SCRYPT_KEY_LEN);
        }
    }

    /** Pure-Java validator for the four scrypt cost parameters
     *  carried in a v=3 slot's {@code kdf.params}. Rejects any slot
     *  whose advertised parameters are weaker than the documented
     *  defaults. Without this guard, an attacker who can place a
     *  slot file (e.g., a malicious Auto-Backup payload) could craft
     *  a v=3 envelope with {@code N=1024} and the user's known
     *  password; the slot's MAC would still verify under those
     *  weakened params (because the MAC key is
     *  {@code HKDF(mainKey, salt, "integrity-v2")} and {@code mainKey}
     *  is the scrypt output), so the only thing standing between a
     *  brute-forceable slot and unlock is this min-bound check.
     *  Mirrored on iOS in {@code StrongboxFileCodec.swift}. Extracted
     *  from {@code decodeOnly} so unit tests can exercise the bound
     *  check without paying the cost of building a full slot envelope
     *  in pure-JVM (where {@code org.json.JSONObject} is stubbed). */
    public static void validateScryptParams(int N, int r, int p, int keyLen)
            throws CodecException {
        if (N < 0 || r < 0 || p < 0 || keyLen < 0) {
            throw new CodecException(ErrorKind.MISSING_FIELD,
                    "kdf.params.{N,r,p,keyLen}");
        }
        if (N < DEFAULT_SCRYPT_N || r < DEFAULT_SCRYPT_R
                || p < DEFAULT_SCRYPT_P || keyLen < DEFAULT_SCRYPT_KEY_LEN) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "kdf.params below documented minimum (got N=" + N
                            + ",r=" + r + ",p=" + p + ",keyLen=" + keyLen
                            + " expected>=N=" + DEFAULT_SCRYPT_N
                            + ",r=" + DEFAULT_SCRYPT_R
                            + ",p=" + DEFAULT_SCRYPT_P
                            + ",keyLen=" + DEFAULT_SCRYPT_KEY_LEN + ")");
        }
    }

    /** Typed view of a slot file's contents. Layer 4 unlocks
     *  {@code passwordWrap} to recover {@code mainKey}, derives
     *  the MAC key via HKDF, verifies {@code mac}, then unwraps
     *  {@code strongbox} and hands the cleartext to layer 5. */
    public static final class DecodedFile {
        public final int v;
        public final long generation;
        public final byte[] kdfSalt;
        public final KdfParams kdfParams;
        public final AeadEnvelope passwordWrap;
        public final AeadEnvelope strongbox;
        public final byte[] uiBlockHash;
        public final Map<String, Object> uiBlock;
        public final byte[] mac;
        public final byte[] macInput;

        DecodedFile(int v, long generation, byte[] kdfSalt, KdfParams kdfParams,
                    AeadEnvelope passwordWrap, AeadEnvelope strongbox,
                    byte[] uiBlockHash, Map<String, Object> uiBlock,
                    byte[] mac, byte[] macInput) {
            this.v = v;
            this.generation = generation;
            this.kdfSalt = kdfSalt;
            this.kdfParams = kdfParams;
            this.passwordWrap = passwordWrap;
            this.strongbox = strongbox;
            this.uiBlockHash = uiBlockHash;
            this.uiBlock = uiBlock;
            this.mac = mac;
            this.macInput = macInput;
        }
    }

    private static final ScheduledExecutorService WRITER_QUEUE =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qc-strongbox-codec-writer");
                t.setDaemon(true);
                return t;
            });

    private StrongboxFileCodec() {}

    // ---- Read ----

    /**
     * Read both slots and return ALL valid candidates ordered
     * by generation descending (winner first, runner-up second).
     * Returns empty list if BOTH slot files are simply absent
     * (first launch).
     *
     * <p><b>(notes for reviewers):</b> the codec layer can only
     * do the structural pre-MAC trial without the user-derived
     * key. The real MAC and AEAD verification require
     * {@code mainKey}, which only layer 4 has. So we return
     * both pre-trial-valid candidates and let layer 4 try them
     * in turn. On a successful fallback, layer 4 surfaces the
     * recovery via {@link StrongboxRedundancyState#markSingleSlot}
     * so the next unlock dialog can warn the user to create a
     * fresh backup.</p>
     */
    public static List<DecodedFile> readCandidates(Context ctx) throws CodecException, IOException {
        AtomicSlotWriter.shared().cleanupTempFiles(ctx);

        byte[] aBytes = AtomicSlotWriter.shared().read(ctx, AtomicSlotWriter.Slot.A);
        byte[] bBytes = AtomicSlotWriter.shared().read(ctx, AtomicSlotWriter.Slot.B);

        if (aBytes == null && bBytes == null) {
            return Collections.emptyList();
        }

        DecodedFile aValid = (aBytes != null) ? tryDecodeAndPreVerify(aBytes) : null;
        DecodedFile bValid = (bBytes != null) ? tryDecodeAndPreVerify(bBytes) : null;

        if (aValid == null && bValid == null) {
            throw new CodecException(ErrorKind.BOTH_SLOTS_INVALID,
                    "StrongboxFileCodec: both slots are invalid (true tamper or first-write race)");
        }
        if (aValid != null && bValid == null) {
            scheduleReMirror(ctx, aValid, AtomicSlotWriter.Slot.B, 0);
            return Collections.singletonList(aValid);
        }
        if (aValid == null && bValid != null) {
            scheduleReMirror(ctx, bValid, AtomicSlotWriter.Slot.A, 0);
            return Collections.singletonList(bValid);
        }
        // Both valid; sort by generation descending.
        List<DecodedFile> out = new ArrayList<>(2);
        if (aValid.generation >= bValid.generation) {
            out.add(aValid);
            out.add(bValid);
        } else {
            out.add(bValid);
            out.add(aValid);
        }
        return out;
    }

    /** Convenience wrapper for callers that only need the
     *  highest-gen valid slot. */
    public static DecodedFile readWinner(Context ctx) throws CodecException, IOException {
        List<DecodedFile> candidates = readCandidates(ctx);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    // ---- Write ----

    /**
     * Encode the supplied component values into the v2 JSON
     * shape, compute the file-level MAC, durably commit the
     * resulting bytes to the inactive slot, AND deep-verify
     * the just-written file before letting the rename promote
     * it.
     *
     * <p><b>Why "deep verify" and not just MAC verify (notes
     * for reviewers):</b>
     * MAC verify alone proves the file we wrote is internally
     * MAC-consistent under our {@code macKey}. That does NOT
     * prove that AEAD-opening the strongbox envelope with our
     * {@code mainKey} will succeed, that the unpad step will not
     * fail, that the JSON decode will round-trip, or that the
     * resulting payload is the same wallets / networks / flags
     * the user just asked us to save. Deep verify proves the
     * entire encode -&gt; pad -&gt; seal -&gt; write -&gt; read
     * -&gt; open -&gt; unpad -&gt; decode round-trip is a no-op
     * for THIS specific payload. A user who taps "Add Wallet"
     * and then re-launches the app is guaranteed to see the
     * wallet because the system literally re-decoded that wallet
     * from disk and confirmed it byte-matches the in-memory
     * payload BEFORE promoting the slot.</p>
     */
    public static void writeNewGeneration(Context ctx, long generation, byte[] kdfSalt,
                                          KdfParams kdfParams, AeadEnvelope passwordWrap,
                                          AeadEnvelope strongbox, byte[] macKey, byte[] mainKey,
                                          StrongboxPayload expectedPayload,
                                          Map<String, Object> uiBlock,
                                          AtomicSlotWriter.Slot currentSlot,
                                          AtomicSlotWriter.PhaseCallback phase)
            throws CodecException, IOException {
        if (uiBlock == null) {
            uiBlock = Collections.emptyMap();
        }
        byte[] uiHash = canonicalUiBlockHash(uiBlock);
        Map<String, Object> mainObj = encodeMainObject(generation, kdfSalt, kdfParams,
                passwordWrap, strongbox, uiHash);
        byte[] macInput = canonicalize(mainObj);
        byte[] macTag = MacUtil.hmacSha256(macInput, macKey);

        Map<String, Object> fullObj = new TreeMap<>(mainObj);
        fullObj.put("mac", b64Enc(macTag));
        fullObj.put("ui", uiBlock);
        byte[] payload = canonicalize(fullObj);

        byte[] expectedCanonical = expectedPayload.canonicalBytesForChecksum();
        AtomicSlotWriter.Slot target = currentSlot.other();

        AtomicSlotWriter.shared().writeAndVerify(ctx, payload, target,
                staged -> {
                    try {
                        deepVerifyStaged(staged, generation, macKey, mainKey,
                                expectedCanonical, expectedPayload);
                    } catch (CodecException ce) {
                        throw new IOException("StrongboxFileCodec.writeNewGeneration: deep verify failed: "
                                + ce.getMessage(), ce);
                    }
                }, phase);
    }

    private static void deepVerifyStaged(byte[] stagedBytes, long expectedGeneration,
                                         byte[] macKey, byte[] mainKey,
                                         byte[] expectedCanonical,
                                         StrongboxPayload expectedPayload)
            throws CodecException {
        // Step A: schema decode (also recomputes uiBlockHash binding).
        DecodedFile staged = decodeOnly(stagedBytes);

        // Step B: generation match guard.
        if (staged.generation != expectedGeneration) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: generation drift asked=" + expectedGeneration
                            + " read=" + staged.generation);
        }

        // Step C: file-level MAC verify.
        if (!MacUtil.verify(staged.macInput, staged.mac, macKey)) {
            throw new CodecException(ErrorKind.MAC_INVALID,
                    "verify: file-level MAC verification failed");
        }

        // Step D: AEAD-open the strongbox envelope with mainKey.
        byte[] paddedPlaintext;
        try {
            paddedPlaintext = Aead.open(staged.strongbox.legacyEnvelopeJson(), mainKey);
        } catch (Aead.AeadException ae) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: strongbox aead open failed: " + ae.getMessage());
        }

        // Step E: strip 32 KiB fixed padding.
        byte[] plaintext;
        try {
            plaintext = StrongboxPadding.unpad(paddedPlaintext);
        } catch (StrongboxPadding.MalformedPaddingException pe) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: padding unpad failed: " + pe.getMessage());
        }

        // Step F: JSON-decode into typed StrongboxPayload.
        StrongboxPayload decoded;
        try {
            decoded = StrongboxPayload.decode(plaintext);
        } catch (Exception e) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: payload decode failed: " + e.getMessage());
        }

        // Step G: inner checksum verify (defense-in-depth).
        if (!decoded.verifyChecksum(mainKey)) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: payload checksum mismatch");
        }

        // Step H: BYTE-COMPARE the round-trip.
        byte[] actualCanonical = decoded.canonicalBytesForChecksum();
        if (!MessageDigest.isEqual(actualCanonical, expectedCanonical)) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "verify: round-trip byte-compare drift "
                            + "(expected=" + expectedCanonical.length + "B "
                            + "actual=" + actualCanonical.length + "B)");
        }
    }

    // ---- File-level MAC verification (called by layer 4) ----

    public static void verifyFileLevelMac(DecodedFile decoded, byte[] macKey)
            throws CodecException {
        if (!MacUtil.verify(decoded.macInput, decoded.mac, macKey)) {
            throw new CodecException(ErrorKind.MAC_INVALID,
                    "StrongboxFileCodec: file-level MAC verification failed");
        }
    }

    // ---- Internals: pre-MAC trial ----

    private static DecodedFile tryDecodeAndPreVerify(byte[] bytes) {
        DecodedFile decoded;
        try {
            decoded = decodeOnly(bytes);
        } catch (CodecException ce) {
            return null;
        }
        // Pre-MAC trial: confirm AEAD envelopes are
        // structurally well-formed by attempting open with a
        // throwaway dummy key. We CANNOT actually verify the
        // tag without the real key; the structural check here
        // is just "does the envelope decode and pass the strict
        // length / shape guards?". The real tag verification
        // happens inside layer 4's Aead.open once mainKey is
        // recovered. Aead.open below WILL throw because we use
        // a wrong key; AUTHENTICATION_FAILED means the envelope
        // shape is structurally fine, MALFORMED_ENVELOPE means
        // it is corrupted.
        byte[] dummyKey = new byte[32];
        for (AeadEnvelope env : new AeadEnvelope[] { decoded.passwordWrap, decoded.strongbox }) {
            try {
                Aead.open(env.legacyEnvelopeJson(), dummyKey);
            } catch (Aead.AeadException ae) {
                if (ae.kind == Aead.ErrorKind.MALFORMED_ENVELOPE) {
                    return null;
                }
                // AUTHENTICATION_FAILED is expected; envelope
                // shape is fine. Continue.
            }
        }
        return decoded;
    }

    private static DecodedFile decodeOnly(byte[] bytes) throws CodecException {
        JSONObject obj;
        try {
            obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException je) {
            throw new CodecException(ErrorKind.MALFORMED_JSON,
                    "top-level not a JSON object: " + je.getMessage());
        }

        int v = obj.optInt("v", -1);
        if (v == -1) throw new CodecException(ErrorKind.MISSING_FIELD, "v");
        if (v != SCHEMA_VERSION) {
            throw new CodecException(ErrorKind.SCHEMA_VERSION_MISMATCH,
                    "schema v=" + v + "; expected " + SCHEMA_VERSION);
        }
        long generation = obj.optLong("generation", -1);
        if (generation == -1) throw new CodecException(ErrorKind.MISSING_FIELD, "generation");

        JSONObject kdf = obj.optJSONObject("kdf");
        if (kdf == null) throw new CodecException(ErrorKind.MISSING_FIELD, "kdf");
        String saltB64 = kdf.optString("salt", null);
        if (saltB64 == null) throw new CodecException(ErrorKind.MISSING_FIELD, "kdf.salt");
        byte[] salt;
        try {
            salt = b64Dec(saltB64);
        } catch (IllegalArgumentException iae) {
            throw new CodecException(ErrorKind.MALFORMED_JSON, "kdf.salt base64 decode failed");
        }
        JSONObject params = kdf.optJSONObject("params");
        if (params == null) throw new CodecException(ErrorKind.MISSING_FIELD, "kdf.params");
        int N = params.optInt("N", -1);
        int r = params.optInt("r", -1);
        int p = params.optInt("p", -1);
        int keyLen = params.optInt("keyLen", -1);
        validateScryptParams(N, r, p, keyLen);
        KdfParams kdfParams = new KdfParams(N, r, p, keyLen);

        JSONObject wrap = obj.optJSONObject("wrap");
        if (wrap == null) throw new CodecException(ErrorKind.MISSING_FIELD, "wrap");
        JSONObject passwordWrapObj = wrap.optJSONObject("passwordWrap");
        if (passwordWrapObj == null) throw new CodecException(ErrorKind.MISSING_FIELD, "wrap.passwordWrap");
        AeadEnvelope passwordWrap = decodeEnvelope(passwordWrapObj);
        if (passwordWrap == null) throw new CodecException(ErrorKind.MALFORMED_JSON, "wrap.passwordWrap shape");

        JSONObject strongboxObj = obj.optJSONObject("strongbox");
        if (strongboxObj == null) throw new CodecException(ErrorKind.MISSING_FIELD, "strongbox");
        AeadEnvelope strongbox = decodeEnvelope(strongboxObj);
        if (strongbox == null) throw new CodecException(ErrorKind.MALFORMED_JSON, "strongbox shape");

        String macB64 = obj.optString("mac", null);
        if (macB64 == null) throw new CodecException(ErrorKind.MISSING_FIELD, "mac");
        byte[] mac;
        try {
            mac = b64Dec(macB64);
        } catch (IllegalArgumentException iae) {
            throw new CodecException(ErrorKind.MALFORMED_JSON, "mac base64 decode failed");
        }

        // (notes for reviewers): the on-disk uiBlockHash MUST
        // match the SHA-256 of the canonical on-disk ui block.
        // A mismatch means the ui block was tampered (someone
        // swapped two slots' ui blocks, or replaced one slot's
        // ui block with attacker-chosen contents). We surface
        // the mismatch as MALFORMED_JSON so the codec's both-
        // INVALID guard fires; layer 4 then reports
        // tamperDetected. Missing uiBlockHash on a legitimate
        // slot is treated as a schema regression and rejected
        // the same way — every writer in this codebase emits
        // the field.
        Map<String, Object> uiObj = jsonObjectToMap(obj.optJSONObject("ui"));
        byte[] recomputedUiHash = canonicalUiBlockHash(uiObj);
        String uiHashB64 = obj.optString("uiBlockHash", null);
        if (uiHashB64 == null) throw new CodecException(ErrorKind.MISSING_FIELD, "uiBlockHash");
        byte[] uiHash;
        try {
            uiHash = b64Dec(uiHashB64);
        } catch (IllegalArgumentException iae) {
            throw new CodecException(ErrorKind.MALFORMED_JSON, "uiBlockHash base64 decode failed");
        }
        if (!MessageDigest.isEqual(uiHash, recomputedUiHash)) {
            throw new CodecException(ErrorKind.MALFORMED_JSON, "ui block hash mismatch");
        }

        // Recompute the MAC input bytes deterministically so
        // layer 4's verification can compare bit-exact.
        Map<String, Object> mainObj = encodeMainObject(generation, salt, kdfParams,
                passwordWrap, strongbox, uiHash);
        byte[] macInput = canonicalize(mainObj);

        return new DecodedFile(v, generation, salt, kdfParams, passwordWrap,
                strongbox, uiHash, uiObj, mac, macInput);
    }

    private static AeadEnvelope decodeEnvelope(JSONObject obj) {
        try {
            String alg = obj.optString("alg", null);
            String ivB64 = obj.optString("iv", null);
            String ctB64 = obj.optString("ct", null);
            String tagB64 = obj.optString("tag", null);
            if (alg == null || ivB64 == null || ctB64 == null || tagB64 == null) return null;
            // Strict alg validation closes the historical typo
            // path. The canonical value is "AES-GCM"; an unknown
            // value at decode time returns null and the slot is
            // treated as malformed.
            if (!EXPECTED_AEAD_ALG.equals(alg)) return null;
            byte[] iv = b64Dec(ivB64);
            byte[] ct = b64Dec(ctB64);
            byte[] tag = b64Dec(tagB64);
            return new AeadEnvelope(alg, iv, ct, tag);
        } catch (IllegalArgumentException iae) {
            return null;
        }
    }

    private static Map<String, Object> encodeEnvelope(AeadEnvelope env) {
        TreeMap<String, Object> out = new TreeMap<>();
        out.put("alg", env.alg);
        out.put("iv", b64Enc(env.iv));
        out.put("ct", b64Enc(env.ct));
        out.put("tag", b64Enc(env.tag));
        return out;
    }

    private static Map<String, Object> encodeMainObject(long generation, byte[] kdfSalt,
                                                        KdfParams kdfParams,
                                                        AeadEnvelope passwordWrap,
                                                        AeadEnvelope strongbox,
                                                        byte[] uiBlockHash) {
        TreeMap<String, Object> wrap = new TreeMap<>();
        wrap.put("passwordWrap", encodeEnvelope(passwordWrap));

        TreeMap<String, Object> kdf = new TreeMap<>();
        kdf.put("algorithm", "scrypt");
        kdf.put("salt", b64Enc(kdfSalt));
        TreeMap<String, Object> params = new TreeMap<>();
        params.put("N", kdfParams.N);
        params.put("r", kdfParams.r);
        params.put("p", kdfParams.p);
        params.put("keyLen", kdfParams.keyLen);
        kdf.put("params", params);

        TreeMap<String, Object> root = new TreeMap<>();
        root.put("v", SCHEMA_VERSION);
        root.put("generation", generation);
        root.put("kdf", kdf);
        root.put("wrap", wrap);
        root.put("strongbox", encodeEnvelope(strongbox));
        root.put("uiBlockHash", b64Enc(uiBlockHash));
        return root;
    }

    /** SHA-256 of the canonical (sortedKeys) JSON of the ui
     *  block. The empty case hashes the canonical bytes of
     *  {@code {}}. Centralised here so ui-canonicalisation has
     *  one source of truth. */
    public static byte[] canonicalUiBlockHash(Map<String, Object> uiBlock) {
        byte[] canonical = canonicalize(uiBlock == null ? Collections.emptyMap() : uiBlock);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Canonicalise to sortedKeys JSON byte-for-byte compatible
     *  with iOS {@code JSONSerialization.data(..., options: .sortedKeys)}.
     *  The implementation walks the Map / List tree and emits
     *  keys in lexicographic order at every level. */
    static byte[] canonicalize(Map<String, ?> obj) {
        StringBuilder sb = new StringBuilder(256);
        writeCanonical(sb, obj);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Testability seam. The codec used to call
     * {@code android.util.Base64} directly, which is not mocked in
     * pure-JVM unit tests (Robolectric is not configured for this
     * module). Routing every encode/decode through these JDK
     * helpers means the codec behaviour is unit-testable end-to-end.
     * The on-the-wire bytes are unchanged: both
     * {@code android.util.Base64.encodeToString(bytes, NO_WRAP)} and
     * {@code java.util.Base64.getEncoder().encodeToString(bytes)}
     * produce RFC 4648 standard base64 with padding and no line
     * wrapping. Symmetric on the decode side.
     */
    static String b64Enc(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static byte[] b64Dec(String s) {
        return Base64.getDecoder().decode(s);
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            // Sort keys lexicographically.
            TreeMap<String, Object> sorted = (m instanceof TreeMap)
                    ? (TreeMap<String, Object>) m : new TreeMap<>(m);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJsonString(e.getKey())).append("\":");
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeCanonical(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Number) {
            // Integers emit as decimal without trailing .0 to
            // match iOS sortedKeys behaviour for Int.
            Number n = (Number) value;
            if (n instanceof Long || n instanceof Integer
                    || n instanceof Short || n instanceof Byte) {
                sb.append(n.longValue());
            } else {
                sb.append(n.toString());
            }
        } else if (value instanceof CharSequence) {
            sb.append('"').append(escapeJsonString(value.toString())).append('"');
        } else {
            sb.append('"').append(escapeJsonString(value.toString())).append('"');
        }
    }

    private static String escapeJsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObjectToMap(JSONObject obj) {
        if (obj == null) return Collections.emptyMap();
        TreeMap<String, Object> out = new TreeMap<>();
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = obj.opt(k);
            if (v instanceof JSONObject) {
                out.put(k, jsonObjectToMap((JSONObject) v));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    // ---- Re-mirror scheduler ----

    /**
     * Re-mirror the surviving slot into the failed slot's path
     * so future reads see redundancy again. Up to TWO retries
     * (immediate + 2-second backoff) before flagging
     * single-slot redundancy state.
     */
    private static void scheduleReMirror(Context ctx, DecodedFile decoded,
                                         AtomicSlotWriter.Slot slot, int attempt) {
        long delaySeconds = (attempt == 0) ? 0L : 2L;
        WRITER_QUEUE.schedule(() -> {
            try {
                // Re-check: if a higher generation has been
                // written to the target slot since readWinner
                // ran, skip the re-mirror so we don't clobber a
                // freshly-committed slot.
                byte[] currentBytes = AtomicSlotWriter.shared().read(ctx, slot);
                if (currentBytes != null) {
                    JSONObject currentObj = new JSONObject(
                            new String(currentBytes, StandardCharsets.UTF_8));
                    long currentGen = currentObj.optLong("generation", -1);
                    if (currentGen >= decoded.generation) {
                        StrongboxRedundancyState.shared().markRedundant();
                        return;
                    }
                }

                Map<String, Object> fullObj = new TreeMap<>(encodeMainObject(
                        decoded.generation, decoded.kdfSalt, decoded.kdfParams,
                        decoded.passwordWrap, decoded.strongbox, decoded.uiBlockHash));
                fullObj.put("mac", b64Enc(decoded.mac));
                // Emit the on-disk ui block VERBATIM from the
                // surviving slot so the re-mirrored slot's
                // recomputed uiBlockHash matches the MAC-bound
                // on-disk value.
                fullObj.put("ui", decoded.uiBlock);

                byte[] bytes = canonicalize(fullObj);
                AtomicSlotWriter.shared().writeAndVerifyBytes(ctx, bytes, slot, null);
                StrongboxRedundancyState.shared().markRedundant();
            } catch (Exception e) {
                Timber.w(e, "scheduleReMirror attempt=%d failed", attempt);
                if (attempt < 2) {
                    scheduleReMirror(ctx, decoded, slot, attempt + 1);
                } else {
                    StrongboxRedundancyState.shared().markSingleSlot();
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}

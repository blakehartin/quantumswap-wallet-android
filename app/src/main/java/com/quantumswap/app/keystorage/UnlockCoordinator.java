package com.quantumswap.app.keystorage;

import android.content.Context;
import android.util.Base64;

import com.quantumswap.app.bridge.QuantumSwapJSBridge;
import com.quantumswap.app.storage.AtomicSlotWriter;
import com.quantumswap.app.strongbox.StrongboxFileCodec;
import com.quantumswap.app.strongbox.StrongboxPadding;
import com.quantumswap.app.strongbox.StrongboxPayload;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import timber.log.Timber;

/**
 * Layer 4 of the storage stack — the unlock / persist / lock
 * orchestrator. Owns the password -&gt; mainKey unlock flow, the
 * persist-with-deep-verify flow, and the anti-rollback generation
 * counter. The decrypted typed payload ({@code livePayload}) is the
 * only post-unlock state cached between calls.
 *
 * <p><b>Why this exists (notes for reviewers):</b>
 * Layer 4 is the only place that:
 * <ul>
 *   <li>knows how to derive {@code mainKey} from password +
 *       {@code passwordWrap} envelope,</li>
 *   <li>knows how to derive the file-level MAC key via HKDF
 *       from {@code mainKey},</li>
 *   <li>knows how to AEAD-seal / AEAD-open the strongbox
 *       payload,</li>
 *   <li>drives the anti-rollback counter so a rolled-back
 *       slot file is rejected.</li>
 * </ul>
 * Layer 5 ({@link StrongboxPayload}) sees only the in-memory
 * typed view; layer 3 ({@link Aead}, {@link MacUtil}) sees
 * only opaque keys + bytes; layer 2 ({@link StrongboxFileCodec})
 * sees a typed {@code DecodedFile}; layer 1
 * ({@link AtomicSlotWriter}) sees only opaque bytes.</p>
 *
 * <p><b>Key lifetime contract (notes for auditors):</b>
 * {@code mainKey} is NEVER cached as a field. {@link #unlock}
 * derives it via scrypt, decrypts the payload into
 * {@link #livePayload}, and zeroes the local 32-byte buffer before
 * returning. {@link #persist} requires a {@code String password}
 * argument, re-derives {@code mainKey} via scrypt on every call,
 * uses it to AEAD-seal the new payload, and zeroes the local
 * buffer in a {@code finally} block. Between calls, only the
 * decrypted {@link #livePayload} (containing wallet metadata,
 * encrypted-at-rest wallet blobs, and secureItems) sits in RAM —
 * never the AES-256 key. Plaintext seed phrases / private keys
 * are not part of {@code livePayload}; per-wallet seeds are
 * encrypted-at-rest inside the wallet JSON and decrypted on
 * demand by the JS bridge.</p>
 *
 * <p><b>Threading:</b> {@link #unlock} and {@link #persist} are
 * synchronous and MUST be called off the UI thread (scrypt takes
 * ~1-3 seconds on modern Android devices). All callers already hop
 * to a background thread before invoking these methods.</p>
 *
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code UnlockCoordinatorV2.swift} exactly, including
 * the per-write scrypt + zeroize contract documented at
 * {@code UnlockCoordinatorV2.persistSnapshot} (the same
 * "long-lived cache of mainKey would extend the in-RAM exposure
 * window for compromise" comment applies here). The same password
 * unlocks a backup file produced by either platform; tests in
 * {@code CrossPlatformBackupTest.java} round-trip iOS-pinned slot
 * files.</p>
 */
public final class UnlockCoordinator {

    /** Outcome of an unlock attempt. Mirrors iOS
     *  {@code UnlockCoordinatorV2Error}. */
    public enum UnlockOutcome {
        /** Password correct; {@code mainKey} loaded into memory. */
        SUCCESS,
        /** Password wrong (AEAD tag verify failed) OR file MAC
         *  failed under the derived key. From the user's
         *  perspective this is "wrong password"; the distinction
         *  between wrong-key and tampered-file is a log-level
         *  detail. */
        WRONG_PASSWORD,
        /** Both slot files were structurally invalid (true
         *  tamper or first-write race). User should restore
         *  from backup. */
        TAMPER_DETECTED,
        /** Anti-rollback counter check failed: the slot file's
         *  generation is older than the counter we last bumped,
         *  meaning someone (or a backup-restore mistake) put an
         *  older valid slot file in place of the current one. */
        ROLLBACK_DETECTED,
        /** The keystore counter could not be read OR storage I/O
         *  failed before we could even attempt unlock. */
        STORAGE_UNAVAILABLE,
        /** App is not yet initialised (no slot files at all).
         *  Caller should branch to first-time-setup flow. */
        NOT_INITIALIZED
    }

    public static final class UnlockResult {
        public final UnlockOutcome outcome;
        public final String diagnostic;

        public UnlockResult(UnlockOutcome outcome, String diagnostic) {
            this.outcome = outcome;
            this.diagnostic = diagnostic;
        }
    }

    /** Phase callback emitted during {@link #persist}. Wraps
     *  the lower-level {@link AtomicSlotWriter.PhaseCallback}
     *  so callers do not have to import the storage layer. */
    public interface PersistPhaseCallback {
        void onPhase(AtomicSlotWriter.WriteVerifyPhase phase);
    }

    private final QuantumSwapJSBridge bridge;
    private final AndroidKeystoreGenerationCounter counter;

    /** Pluggable RNG seam. Production wires a
     *  {@link DefaultSecureRandomSource}; the deterministic v=3
     *  cross-platform vector tests inject a test source so the
     *  scrypt salt + mainKey + AEAD IVs in
     *  {@code tests/fixtures/strongbox-v3-vectors/} reproduce
     *  byte-for-byte. {@link #setRandomSourceForTest(SecureRandomSource)}
     *  is the only way to swap it; that setter is annotated and
     *  package-private to make accidental production use
     *  obvious in code review. */
    private SecureRandomSource randomSource = new DefaultSecureRandomSource();

    /** Test-only seam used by the v=3 cross-platform vector
     *  suite. Replacing this in production would re-route every
     *  scrypt salt + mainKey draw through caller-controlled
     *  bytes, which would be a critical key-recovery bug. The
     *  setter is package-private and tagged so any production
     *  caller surfaces in PR review. */
    @androidx.annotation.VisibleForTesting
    void setRandomSourceForTest(SecureRandomSource source) {
        if (source == null) {
            throw new IllegalArgumentException("randomSource must not be null");
        }
        this.randomSource = source;
    }

    /** Live in-memory typed payload mirror so callers do not
     *  re-decrypt every read. Updated by {@link #unlock} and by
     *  {@link #persist} on success. The decrypted payload is the
     *  ONLY post-unlock state we cache; the AES-256 mainKey is
     *  derived locally per call and zeroed before each method
     *  returns. */
    private volatile StrongboxPayload livePayload;

    /** Active read-winner DecodedFile (kept so persist() knows
     *  which slot to rotate). */
    private volatile StrongboxFileCodec.DecodedFile activeDecoded;

    /** Active KdfParams + salt, copied from the live slot so
     *  persist() can re-seal under the same scrypt parameters
     *  without re-deriving from a future-changed default. */
    private volatile byte[] activeKdfSalt;
    private volatile StrongboxFileCodec.KdfParams activeKdfParams;

    /** Last-loaded ui block (preserved verbatim so a re-write
     *  that does not change UI prefs keeps the same hash). */
    private volatile Map<String, Object> liveUiBlock = Collections.emptyMap();

    public UnlockCoordinator(Context ctx, QuantumSwapJSBridge bridge) {
        this.bridge = bridge;
        this.counter = new AndroidKeystoreGenerationCounter(ctx);
    }

    /** True iff at least one slot file exists. */
    public boolean isInitialized(Context ctx) {
        try {
            return AtomicSlotWriter.shared().read(ctx, AtomicSlotWriter.Slot.A) != null
                    || AtomicSlotWriter.shared().read(ctx, AtomicSlotWriter.Slot.B) != null;
        } catch (IOException ioe) {
            return false;
        }
    }

    /** True iff a decrypted payload snapshot is loaded (i.e. the
     *  user has authenticated this session). The AES-256 mainKey
     *  itself is never cached; only the decrypted typed payload
     *  is held between calls. See class Javadoc. */
    public boolean isUnlocked() {
        return livePayload != null;
    }

    /** Wipe in-memory secrets. Idempotent. */
    public void lock() {
        livePayload = null;
        activeDecoded = null;
        activeKdfSalt = null;
        activeKdfParams = null;
        liveUiBlock = Collections.emptyMap();
    }

    /**
     * Read-only snapshot of the live payload. Returns null if
     * the coordinator is locked. Callers MUST treat the result
     * as immutable and NOT mutate it; mutations need
     * {@link #persist}.
     */
    public StrongboxPayload getLivePayload() {
        return livePayload;
    }

    /**
     * Create a fresh strongbox: generate salt + mainKey, derive
     * the password-wrapping key via scrypt, AEAD-seal the
     * (initially-empty) payload under mainKey, then write
     * generation=1 to slot A.
     *
     * <p><b>(notes for reviewers):</b> the initial payload is
     * empty wallet list + empty customNetworks; v=3 derives
     * the max-wallet-index from the wallets map keys so there
     * is nothing to seed here. The first {@code saveWallet}
     * call after this then generation-bumps to 2 and persists
     * the actual wallet. This shape lets the create-wallet UX
     * show a unlock dialog before any wallet exists, mirroring
     * iOS.</p>
     */
    public void createNewStrongbox(Context ctx, String password)
            throws GeneralSecurityException, IOException {
        byte[] salt = new byte[32];
        randomSource.nextBytes(salt);
        byte[] newMainKey = new byte[32];
        randomSource.nextBytes(newMainKey);

        StrongboxFileCodec.KdfParams kdfParams = StrongboxFileCodec.KdfParams.defaults();
        byte[] derivedKey = deriveKeyViaScrypt(password, salt, kdfParams);
        StrongboxFileCodec.AeadEnvelope passwordWrap;
        try {
            passwordWrap = StrongboxFileCodec.AeadEnvelope.fromAeadSeal(
                    Aead.seal(newMainKey, derivedKey, randomSource));
        } finally {
            Arrays.fill(derivedKey, (byte) 0);
        }

        StrongboxPayload payload = new StrongboxPayload();
        payload.stampChecksum(newMainKey);

        byte[] padded = padPayload(payload, newMainKey);
        StrongboxFileCodec.AeadEnvelope strongbox = StrongboxFileCodec.AeadEnvelope.fromAeadSeal(
                Aead.seal(padded, newMainKey, randomSource));
        byte[] macKey = MacUtil.hkdfExtractAndExpand(newMainKey, salt,
                MacUtil.INTEGRITY_INFO_LABEL, MacUtil.HMAC_LEN);
        try {
            // First write goes to slot B (treat A as the
            // "current" slot so .other() = B).
            try {
                StrongboxFileCodec.writeNewGeneration(ctx, 1L, salt, kdfParams,
                        passwordWrap, strongbox, macKey, newMainKey, payload,
                        liveUiBlock, AtomicSlotWriter.Slot.A, null);
            } catch (StrongboxFileCodec.CodecException ce) {
                throw new GeneralSecurityException("createNewStrongbox: codec failed", ce);
            }

            // Bump the keystore counter to match the slot
            // generation. createNewStrongbox is also the path
            // that re-creates the counter after a full reset.
            counter.reset();
            counter.setCounter(1L);

            this.livePayload = payload;
            this.activeKdfSalt = salt;
            this.activeKdfParams = kdfParams;
            // Re-read so activeDecoded reflects on-disk state.
            try {
                this.activeDecoded = StrongboxFileCodec.readWinner(ctx);
            } catch (StrongboxFileCodec.CodecException ce) {
                throw new GeneralSecurityException("createNewStrongbox: post-write read failed", ce);
            }
        } finally {
            // Zero every byte of secret material we touched. The
            // mainKey is NOT cached on a field; subsequent persist
            // calls will re-derive it from the user's password.
            Arrays.fill(macKey, (byte) 0);
            Arrays.fill(padded, (byte) 0);
            Arrays.fill(newMainKey, (byte) 0);
        }
    }

    /**
     * Try to unlock with {@code password}. Tries each candidate
     * slot in generation-descending order (so the runner-up is a
     * fallback if the winner fails the file-MAC step).
     */
    public UnlockResult unlock(Context ctx, String password) {
        java.util.List<StrongboxFileCodec.DecodedFile> candidates;
        try {
            candidates = StrongboxFileCodec.readCandidates(ctx);
        } catch (StrongboxFileCodec.CodecException ce) {
            if (ce.kind == StrongboxFileCodec.ErrorKind.BOTH_SLOTS_INVALID) {
                return new UnlockResult(UnlockOutcome.TAMPER_DETECTED, ce.getMessage());
            }
            return new UnlockResult(UnlockOutcome.STORAGE_UNAVAILABLE, ce.getMessage());
        } catch (IOException ioe) {
            return new UnlockResult(UnlockOutcome.STORAGE_UNAVAILABLE, ioe.getMessage());
        }
        if (candidates.isEmpty()) {
            return new UnlockResult(UnlockOutcome.NOT_INITIALIZED, "no slot files present");
        }

        long keystoreCounter;
        try {
            keystoreCounter = counter.getCounter();
        } catch (GeneralSecurityException gse) {
            return new UnlockResult(UnlockOutcome.STORAGE_UNAVAILABLE,
                    "counter read failed: " + gse.getMessage());
        }

        // Cache the first scrypt result across candidates when
        // their salts match (the common case).
        byte[] cachedDerived = null;
        byte[] cachedSaltSig = null;
        UnlockResult lastFailure = null;
        for (StrongboxFileCodec.DecodedFile candidate : candidates) {
            if (cachedDerived == null
                    || !Arrays.equals(cachedSaltSig, candidate.kdfSalt)) {
                if (cachedDerived != null) Arrays.fill(cachedDerived, (byte) 0);
                try {
                    cachedDerived = deriveKeyViaScrypt(password, candidate.kdfSalt,
                            candidate.kdfParams);
                    cachedSaltSig = candidate.kdfSalt.clone();
                } catch (GeneralSecurityException gse) {
                    return new UnlockResult(UnlockOutcome.STORAGE_UNAVAILABLE,
                            "scrypt failed: " + gse.getMessage());
                }
            }
            UnlockResult attempt = tryUnlockOneCandidate(candidate, cachedDerived,
                    keystoreCounter);
            if (attempt.outcome == UnlockOutcome.SUCCESS) {
                if (cachedDerived != null) Arrays.fill(cachedDerived, (byte) 0);
                return attempt;
            }
            lastFailure = attempt;
            // If the failure was rollback or wrong password we
            // still try the runner-up candidate. Wrong-password
            // on the winner with a successful unlock on the
            // runner-up means the winner is a stale slot we
            // should not trust.
        }
        if (cachedDerived != null) Arrays.fill(cachedDerived, (byte) 0);
        return lastFailure != null ? lastFailure
                : new UnlockResult(UnlockOutcome.WRONG_PASSWORD, "all candidates failed");
    }

    private UnlockResult tryUnlockOneCandidate(StrongboxFileCodec.DecodedFile candidate,
                                               byte[] derivedKey, long keystoreCounter) {
        // Step 1: AEAD-open the password wrap envelope.
        byte[] mainKeyBytes;
        try {
            mainKeyBytes = Aead.open(candidate.passwordWrap.legacyEnvelopeJson(), derivedKey);
        } catch (Aead.AeadException ae) {
            return new UnlockResult(UnlockOutcome.WRONG_PASSWORD,
                    "passwordWrap open failed: " + ae.kind);
        }

        // Step 2: derive macKey via HKDF.
        byte[] macKey = MacUtil.hkdfExtractAndExpand(mainKeyBytes, candidate.kdfSalt,
                MacUtil.INTEGRITY_INFO_LABEL, MacUtil.HMAC_LEN);
        try {
            // Step 3: file-level MAC verify.
            try {
                StrongboxFileCodec.verifyFileLevelMac(candidate, macKey);
            } catch (StrongboxFileCodec.CodecException ce) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                return new UnlockResult(UnlockOutcome.WRONG_PASSWORD,
                        "file mac failed: " + ce.getMessage());
            }

            // Step 4: AEAD-open the strongbox envelope.
            byte[] padded;
            try {
                padded = Aead.open(candidate.strongbox.legacyEnvelopeJson(), mainKeyBytes);
            } catch (Aead.AeadException ae) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                return new UnlockResult(UnlockOutcome.WRONG_PASSWORD,
                        "strongbox aead open failed: " + ae.kind);
            }

            // Step 5: unpad.
            byte[] plaintext;
            try {
                plaintext = StrongboxPadding.unpad(padded);
            } catch (StrongboxPadding.MalformedPaddingException pe) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                Arrays.fill(padded, (byte) 0);
                return new UnlockResult(UnlockOutcome.TAMPER_DETECTED,
                        "padding malformed: " + pe.getMessage());
            }
            Arrays.fill(padded, (byte) 0);

            // Step 6: decode + checksum verify.
            StrongboxPayload payload;
            try {
                payload = StrongboxPayload.decode(plaintext);
            } catch (Exception e) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                Arrays.fill(plaintext, (byte) 0);
                return new UnlockResult(UnlockOutcome.TAMPER_DETECTED,
                        "payload decode failed: " + e.getMessage());
            }
            Arrays.fill(plaintext, (byte) 0);
            if (!payload.verifyChecksum(mainKeyBytes)) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                return new UnlockResult(UnlockOutcome.TAMPER_DETECTED,
                        "payload checksum mismatch");
            }

            // Step 7: anti-rollback check.
            if (keystoreCounter != -1L && candidate.generation < keystoreCounter) {
                Arrays.fill(mainKeyBytes, (byte) 0);
                return new UnlockResult(UnlockOutcome.ROLLBACK_DETECTED,
                        "slot generation " + candidate.generation
                                + " < keystore counter " + keystoreCounter);
            }
            // Heal-forward: if the slot generation is greater
            // than the keystore counter, bump the counter (a
            // power-loss between rename and counter-bump put us
            // in this state).
            if (candidate.generation > keystoreCounter) {
                try {
                    counter.setCounter(candidate.generation);
                } catch (GeneralSecurityException gse) {
                    Timber.w(gse, "unlock: heal-forward counter bump failed");
                }
            }
            // First-time-counter case: keystoreCounter == -1.
            // Initialise counter to slot generation so future
            // rollback checks have a baseline.
            if (keystoreCounter == -1L) {
                try {
                    counter.setCounter(candidate.generation);
                } catch (GeneralSecurityException gse) {
                    Timber.w(gse, "unlock: initial counter set failed");
                }
            }

            // Commit live state. mainKey is NOT cached on a field;
            // subsequent persist calls re-derive it from the user
            // password and zero the local buffer on each call. Mirrors
            // iOS UnlockCoordinatorV2 (no cached mainKey field).
            this.livePayload = payload;
            this.activeDecoded = candidate;
            this.activeKdfSalt = candidate.kdfSalt;
            this.activeKdfParams = candidate.kdfParams;
            this.liveUiBlock = candidate.uiBlock;
            Arrays.fill(mainKeyBytes, (byte) 0);
            return new UnlockResult(UnlockOutcome.SUCCESS, "ok");
        } finally {
            Arrays.fill(macKey, (byte) 0);
        }
    }

    /**
     * Verify-only check of {@code password}. Does NOT change
     * live unlock state. Used by SendFragment to gate a
     * sensitive action while a session is already unlocked.
     */
    public boolean verifyPassword(Context ctx, String password) {
        try {
            StrongboxFileCodec.DecodedFile winner = StrongboxFileCodec.readWinner(ctx);
            if (winner == null) return false;
            byte[] derivedKey = deriveKeyViaScrypt(password, winner.kdfSalt, winner.kdfParams);
            try {
                Aead.open(winner.passwordWrap.legacyEnvelopeJson(), derivedKey);
                return true;
            } catch (Aead.AeadException ae) {
                return false;
            } finally {
                Arrays.fill(derivedKey, (byte) 0);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Persist {@code newPayload} to disk. Caller MUST be
     * unlocked AND must supply the user's current password so we
     * can re-derive {@code mainKey} for this single seal operation.
     *
     * <p><b>Key-lifetime contract:</b> we re-derive {@code mainKey}
     * via scrypt + AEAD-open of the on-disk passwordWrap envelope
     * on every call, AEAD-seal the payload, and zero the local
     * 32-byte buffer in a {@code finally} block. The AES-256 key
     * is never stored on a field. Cost: one scrypt per persist
     * (~1-3s on Android). Mirrors iOS
     * {@code UnlockCoordinatorV2.persistSnapshot}.</p>
     *
     * <p>The persist is atomic-rotate-with-deep-verify: a failure
     * leaves the prior state intact (no slot rename, no counter
     * bump).</p>
     *
     * <p><b>Brute-force protection:</b> {@link UnlockAttemptLimiter}
     * is consulted before paying scrypt; a wrong password is recorded
     * as a failure, a successful persist resets the counter. This
     * makes the persist surface (which collects user passwords) a
     * rate-limited brute-force surface in its own right.</p>
     *
     * @throws TooManyAttemptsException if the limiter is engaged
     * @throws WrongPasswordException   if the password fails AEAD-open
     * @throws GeneralSecurityException for any other crypto failure
     * @throws IOException              for storage failures
     */
    public synchronized void persist(Context ctx, StrongboxPayload newPayload,
                                     String password,
                                     PersistPhaseCallback uiPhase)
            throws GeneralSecurityException, IOException {
        if (livePayload == null) {
            throw new GeneralSecurityException("persist: not unlocked (no live payload)");
        }
        if (activeDecoded == null || activeKdfSalt == null || activeKdfParams == null) {
            throw new GeneralSecurityException("persist: missing active slot context");
        }
        if (password == null) {
            throw new GeneralSecurityException("persist: password is required");
        }

        // Brute-force limiter pre-check before paying scrypt cost.
        // Mirrors iOS persistSnapshot limiter check
        // (UnlockCoordinatorV2.swift lines 1143-1148).
        com.quantumswap.app.security.UnlockAttemptLimiter.Decision decision =
                com.quantumswap.app.security.UnlockAttemptLimiter.currentDecision(ctx);
        if (decision.kind == com.quantumswap.app.security.UnlockAttemptLimiter
                .DecisionKind.LOCKED) {
            throw new TooManyAttemptsException(decision.remainingSeconds);
        }

        long newGeneration = activeDecoded.generation + 1L;
        // Re-use the same passwordWrap envelope verbatim so the
        // user's password is not rotated by this write.
        StrongboxFileCodec.AeadEnvelope passwordWrap = activeDecoded.passwordWrap;

        // Derive mainKey from password via scrypt + AEAD-open of
        // the wrap envelope. The local buffer is zeroed in the
        // finally block; we never store it on a field.
        byte[] derivedKey = deriveKeyViaScrypt(password, activeKdfSalt, activeKdfParams);
        byte[] mainKey = null;
        byte[] padded = null;
        byte[] macKey = null;
        try {
            try {
                mainKey = Aead.open(passwordWrap.legacyEnvelopeJson(), derivedKey);
            } catch (Aead.AeadException ae) {
                // Wrong password on a write surface counts against
                // the same limiter as the unlock dialog (matches iOS
                // UnlockCoordinatorV2 lines 1175-1183).
                com.quantumswap.app.security.UnlockAttemptLimiter.recordFailure(ctx,
                        com.quantumswap.app.security.UnlockAttemptLimiter
                                .Channel.STRONGBOX_UNLOCK);
                throw new WrongPasswordException(
                        "persist: wrong password (passwordWrap open failed: " + ae.kind + ")");
            }

            padded = padPayload(newPayload, mainKey);
            StrongboxFileCodec.AeadEnvelope strongbox;
            try {
                strongbox = StrongboxFileCodec.AeadEnvelope.fromAeadSeal(Aead.seal(padded, mainKey, randomSource));
            } catch (StrongboxFileCodec.CodecException ce) {
                throw new GeneralSecurityException(
                        "persist: AEAD seal failed: " + ce.getMessage(), ce);
            }

            macKey = MacUtil.hkdfExtractAndExpand(mainKey, activeKdfSalt,
                    MacUtil.INTEGRITY_INFO_LABEL, MacUtil.HMAC_LEN);

            AtomicSlotWriter.PhaseCallback inner = uiPhase == null ? null
                    : phase -> uiPhase.onPhase(phase);
            try {
                StrongboxFileCodec.writeNewGeneration(ctx, newGeneration, activeKdfSalt,
                        activeKdfParams, passwordWrap, strongbox, macKey, mainKey,
                        newPayload, liveUiBlock,
                        activeSlotForCurrent(ctx), inner);
            } catch (StrongboxFileCodec.CodecException ce) {
                throw new GeneralSecurityException(
                        "persist: codec write failed: " + ce.getMessage(), ce);
            }

            counter.setCounter(newGeneration);
            try {
                this.activeDecoded = StrongboxFileCodec.readWinner(ctx);
            } catch (StrongboxFileCodec.CodecException ce) {
                throw new GeneralSecurityException("persist: post-write read failed", ce);
            }
            this.livePayload = newPayload;

            // No pre-unlock mirror block by design. The pref
            // (`PrefConnect.BACKUP_ENABLED_KEY`) is the SOLE source
            // of truth for the backup-enabled toggle; Settings and
            // onboarding write it directly when the user toggles,
            // and the encrypted payload does not carry a mirror
            // copy. `WalletBackupAgent.onFullBackup` reads the pref
            // pre-unlock. This avoids any parity gap where a
            // force-kill between SettingsFragment's pref write and
            // the next persist could leave pref and payload
            // disagreeing about the user's choice. Mirrored on iOS
            // in `BackupExclusion.swift` / `Strongbox.swift`.

            // User proved knowledge of the current password.
            com.quantumswap.app.security.UnlockAttemptLimiter.recordSuccess(ctx,
                    com.quantumswap.app.security.UnlockAttemptLimiter
                            .Channel.STRONGBOX_UNLOCK);
        } finally {
            Arrays.fill(derivedKey, (byte) 0);
            if (mainKey != null) Arrays.fill(mainKey, (byte) 0);
            if (padded != null) Arrays.fill(padded, (byte) 0);
            if (macKey != null) Arrays.fill(macKey, (byte) 0);
        }
    }

    /** Resolve the *currently-loaded* slot identity by comparing
     *  on-disk slot generations to {@code activeDecoded.generation}.
     *  This lets persist() correctly rotate to the OTHER slot
     *  even if we re-read after an external modification. */
    private AtomicSlotWriter.Slot activeSlotForCurrent(Context ctx) throws IOException {
        long activeGen = activeDecoded.generation;
        byte[] aBytes = AtomicSlotWriter.shared().read(ctx, AtomicSlotWriter.Slot.A);
        if (aBytes != null) {
            try {
                JSONObject obj = new JSONObject(new String(aBytes,
                        java.nio.charset.StandardCharsets.UTF_8));
                if (obj.optLong("generation", -1) == activeGen) {
                    return AtomicSlotWriter.Slot.A;
                }
            } catch (JSONException ignore) {}
        }
        return AtomicSlotWriter.Slot.B;
    }

    // ---- Internals ----

    private byte[] padPayload(StrongboxPayload payload, byte[] mainKey)
            throws GeneralSecurityException {
        byte[] plaintext = payload.encodeForSeal(mainKey);
        try {
            return StrongboxPadding.pad(plaintext);
        } catch (StrongboxPadding.PlaintextTooLargeException pe) {
            throw new GeneralSecurityException("strongbox payload exceeds bucket: "
                    + pe.actualBytes + " > " + pe.bucketBytes, pe);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] deriveKeyViaScrypt(String password, byte[] salt,
                                      StrongboxFileCodec.KdfParams params)
            throws GeneralSecurityException {
        // Defense-in-depth bound. The codec already rejects sub-
        // minimum params at decode time, but if a future caller
        // ever bypasses StrongboxFileCodec.decodeOnly (e.g., a unit
        // test or a migration tool that constructs KdfParams
        // directly) this guard keeps us from running scrypt with
        // weakened cost.
        if (params.N < StrongboxFileCodec.DEFAULT_SCRYPT_N
                || params.r < StrongboxFileCodec.DEFAULT_SCRYPT_R
                || params.p < StrongboxFileCodec.DEFAULT_SCRYPT_P
                || params.keyLen < StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN) {
            throw new GeneralSecurityException(
                    "scrypt parameters below documented minimum (got N="
                            + params.N + ",r=" + params.r + ",p=" + params.p
                            + ",keyLen=" + params.keyLen
                            + " expected>=N=" + StrongboxFileCodec.DEFAULT_SCRYPT_N
                            + ",r=" + StrongboxFileCodec.DEFAULT_SCRYPT_R
                            + ",p=" + StrongboxFileCodec.DEFAULT_SCRYPT_P
                            + ",keyLen=" + StrongboxFileCodec.DEFAULT_SCRYPT_KEY_LEN + ")");
        }
        String saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP);
        try {
            String resultJson = bridge.scryptDerive(password, saltB64,
                    params.N, params.r, params.p, params.keyLen);
            JSONObject json = new JSONObject(resultJson);
            JSONObject data = json.getJSONObject("data");
            String keyBase64 = data.getString("key");
            return Base64.decode(keyBase64, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new GeneralSecurityException("scrypt derive failed: " + e.getMessage(), e);
        }
    }
}

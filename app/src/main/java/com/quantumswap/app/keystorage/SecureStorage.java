package com.quantumswap.app.keystorage;

import android.content.Context;

import com.quantumswap.app.bridge.QuantumSwapJSBridge;
import com.quantumswap.app.strongbox.StrongboxPayload;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

/**
 * Password-encrypted wallet storage facade. Today this class is a
 * thin compatibility wrapper over {@link UnlockCoordinator} and the
 * v2 layered strongbox stack ({@link com.quantumswap.app.strongbox.StrongboxFileCodec},
 * {@link com.quantumswap.app.storage.AtomicSlotWriter},
 * {@link com.quantumswap.app.strongbox.StrongboxPadding},
 * {@link Aead}, {@link MacUtil}).
 * <p>the legacy per-wallet
 * SharedPreferences-key model has been replaced by a single
 * AEAD-sealed strongbox file rotated across two slots with
 * F_FULLFSYNC + read-back-and-byte-compare deep verify. The
 * legacy {@code saveWallet}/{@code loadWallet}/{@code setSecureItem}
 * API is preserved here so existing call sites (HomeActivity,
 * HomeWalletFragment, SendFragment, RevealWalletFragment,
 * WalletsFragment) continue to compile; internally each call now
 * mutates the in-memory payload and re-persists the entire
 * strongbox via {@link UnlockCoordinator#persist}. The slot file
 * size is fixed at 32 KiB so wallet count cannot be inferred
 * from on-disk file length (closes the size-leak threat).</p>
 * Cryptographic design (matches iOS):
 * <ul>
 *   <li>Password is stretched with Scrypt (N=2^18, r=8, p=1,
 *       32-byte output). The derived key only ever encrypts the
 *       random 32-byte {@code mainKey} (passwordWrap envelope).</li>
 *   <li>{@code mainKey} encrypts the entire strongbox payload
 *       with AES-256-GCM, fresh 12-byte IV, 128-bit tag.</li>
 *   <li>The strongbox is wrapped in a slot-file JSON that
 *       carries an HMAC-SHA-256 file-level MAC keyed by
 *       HKDF-SHA-256(mainKey, salt, "integrity-v2"). The MAC
 *       binds the v, generation, kdf, wrap, strongbox, and
 *       uiBlockHash fields together so a tampered slot is
 *       rejected.</li>
 *   <li>An anti-rollback counter signed by an
 *       AndroidKeyStore-bound HMAC key (StrongBox-backed when
 *       available, TEE-backed otherwise) prevents an attacker
 *       from replacing the live slot with an older legitimate
 *       slot.</li>
 * </ul>
 * Wrong password / tampered blob surfaces as {@code unlock(...)
 * == false} so callers cannot padding-oracle the store.</p>
 * <p><b>Why the master key is NOT wrapped by AndroidKeyStore
 * (architectural decision, preserved from the legacy design):</b>
 * The encrypted {@code mainKey} and all per-wallet blobs must
 * remain <i>portable across devices</i> so users can recover
 * their wallets when they migrate to a new phone or restore
 * from a cloud / device backup. AndroidKeyStore-wrapped
 * material is bound to the originating device's TEE / StrongBox
 * and cannot be moved off-device, which would break the
 * product's phone-migration and opt-in cloud-backup story. The
 * trade-off (an offline scrypt attack is possible if a rooted
 * attacker exfiltrates the encrypted blob) is accepted because
 * key portability is a product requirement; scrypt parameters
 * (N=2^18, r=8, p=1) are tuned to make that offline attack
 * expensive against anything but a trivial password.</p>
 */
public class SecureStorage {

    private final UnlockCoordinator coordinator;

    public SecureStorage(Context appCtx, QuantumSwapJSBridge bridge) {
        this.coordinator = new UnlockCoordinator(appCtx, bridge);
    }

    /** Exposed for callers (e.g. backup restore) that need the
     *  richer outcome enum. */
    public UnlockCoordinator getCoordinator() {
        return coordinator;
    }

    public boolean isInitialized(Context ctx) {
        return coordinator.isInitialized(ctx);
    }

    public boolean isUnlocked() {
        return coordinator.isUnlocked();
    }

     /**
     * Verify-only check of {@code password} against the strongbox
     * wrap envelope. Does NOT change live unlock state. Cost is a
     * single scrypt + AEAD-open of the wrap envelope; the derived
     * mainKey is zeroized in a {@code finally} block inside
     * {@link UnlockCoordinator#verifyPassword}.
     * <p>Used by the cloud-restore prompt when the strongbox is
     * already unlocked, to capture the strongbox-write password
     * (which can legitimately differ from the per-file backup
     * password) without a heavyweight re-unlock.</p>
     * <p>Callers MUST run this on a background thread (~1-3s on
     * mid-range hardware) and MUST consult
     * {@link com.quantumswap.app.security.UnlockAttemptLimiter}
     * before calling: this method itself does NOT record limiter
     * events so the calling site can pick the appropriate
     * channel.</p>
     */
    public boolean verifyPassword(Context ctx, String password) {
        return coordinator.verifyPassword(ctx, password);
    }

     /**
     * First-time setup: bootstrap a fresh strongbox under the
     * supplied password. Must be called from a background
     * thread (scrypt takes ~1-3 s).
     */
    public void createMainKey(Context ctx, String password) throws Exception {
        coordinator.createNewStrongbox(ctx, password);
    }

     /**
     * Unlock with {@code password}. Returns {@code true} on
     * success, {@code false} on wrong password / tamper /
     * rollback. Must be called from a background thread.
     * <p>the four failure modes
     * (wrong password, tamper, rollback, storage unavailable)
     * are uniformly mapped to {@code false} for legacy callers
     * that just want a boolean. New callers should use
     * {@link UnlockCoordinator#unlock} directly to get the
     * structured outcome and present a precise error to the
     * user (e.g. "device tampered with — restore from backup").</p>
     */
    public boolean unlock(Context ctx, String password) {
        UnlockCoordinator.UnlockResult result = coordinator.unlock(ctx, password);
        if (result.outcome != UnlockCoordinator.UnlockOutcome.SUCCESS) {
            Timber.w("SecureStorage.unlock failed: %s (%s)",
                    result.outcome, result.diagnostic);
            return false;
        }
        // Run one-shot post-unlock migrations. Currently this
        // moves user-added blockchain networks and the
        // active-network index from the legacy plaintext
        // SharedPreferences keys into StrongboxPayload.networks /
        // currentNetworkId (mirrors iOS's behavior of keeping
        // user-added networks inside the encrypted strongbox).
        // Idempotent — subsequent calls are no-ops once the
        // legacy keys are cleared. The password is threaded through
        // because the migration may need to persist (which now
        // requires the user password to re-derive mainKey).
        try {
            com.quantumswap.app.utils.NetworkPersistence
                    .migrateLegacyOnUnlockIfNeeded(ctx, this, password);
        } catch (Throwable t) {
            Timber.w(t, "SecureStorage.unlock: post-unlock migration failed");
        }
        return true;
    }

    public void lock() {
        coordinator.lock();
    }

     /**
     * Build in-memory address maps from the live strongbox
     * payload. Returns {addressToIndex, indexToAddress} pair.
     * Must be called when unlocked.
     */
    public Map<String, String>[] buildWalletMaps(Context ctx) throws Exception {
        requireUnlocked();
        StrongboxPayload payload = coordinator.getLivePayload();
        Map<String, String> addressToIndex = new HashMap<>();
        Map<String, String> indexToAddress = new HashMap<>();
        if (payload != null && payload.wallets != null) {
            for (Map.Entry<String, String> e : payload.wallets.entrySet()) {
                String idxStr = e.getKey();
                String encoded = e.getValue();
                if (encoded == null) continue;
                try {
                    com.quantumswap.app.strongbox.WalletEntryCodec.WalletEntry entry =
                            com.quantumswap.app.strongbox.WalletEntryCodec
                                    .decodeEntry(encoded);
                    String address = entry.address;
                    if (address == null || address.isEmpty()) {
                        Timber.w("buildWalletMaps: empty address at index %s", idxStr);
                        continue;
                    }
                    addressToIndex.put(address, idxStr);
                    indexToAddress.put(idxStr, address);
                } catch (Exception ex) {
                    Timber.w(ex, "buildWalletMaps: malformed wallet at index %s", idxStr);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String>[] result = new Map[]{addressToIndex, indexToAddress};
        return result;
    }

    public void setSecureItem(Context ctx, String key, String value, String password)
            throws Exception {
        requireUnlocked();
        StrongboxPayload payload = coordinator.getLivePayload();
        payload.secureItems.put(key, value);
        coordinator.persist(ctx, payload, password, null);
    }

    public String getSecureItem(Context ctx, String key) throws Exception {
        requireUnlocked();
        StrongboxPayload payload = coordinator.getLivePayload();
        if (payload == null || payload.secureItems == null) return null;
        return payload.secureItems.get(key);
    }

    public void removeSecureItem(Context ctx, String key, String password) {
        try {
            requireUnlocked();
            StrongboxPayload payload = coordinator.getLivePayload();
            if (payload == null || payload.secureItems == null) return;
            if (payload.secureItems.remove(key) != null) {
                coordinator.persist(ctx, payload, password, null);
            }
        } catch (Exception e) {
            Timber.w(e, "removeSecureItem(%s) failed", key);
        }
    }

    public void saveWallet(Context ctx, int index, String walletJson, String password)
            throws Exception {
        requireUnlocked();
        // Encode at the storage boundary into the compact
        // binary wire form (length-prefixed raw bytes for the
        // post-quantum keys, base64-wrapped only because the
        // outer JSON payload demands a string value). Read
        // sites still see the legacy JSON shape via loadWallet.
        // Background: see WalletEntryCodec class header.
        String encoded = com.quantumswap.app.strongbox.WalletEntryCodec.encode(walletJson);
        StrongboxPayload payload = coordinator.getLivePayload();
        payload.wallets.put(String.valueOf(index), encoded);
        // v=3: maxWalletIndex is derived on demand from the
        // wallets map keys (see StrongboxPayload.maxWalletIndex())
        // so there is no longer a redundant scalar to update.
        coordinator.persist(ctx, payload, password, null);
    }

    public String loadWallet(Context ctx, int index) throws Exception {
        requireUnlocked();
        StrongboxPayload payload = coordinator.getLivePayload();
        if (payload == null || payload.wallets == null) return null;
        String encoded = payload.wallets.get(String.valueOf(index));
        if (encoded == null) return null;
        return com.quantumswap.app.strongbox.WalletEntryCodec.decode(encoded);
    }

    public int getMaxWalletIndex(Context ctx) {
        if (!coordinator.isUnlocked()) {
            // Pre-unlock callers may still ask for the max index
            // (e.g. to gate "do we have any wallets" UI). Without
            // a decrypted snapshot we cannot read the strongbox,
            // so report -1 (no wallets visible). Once unlocked the
            // live value is returned.
            return -1;
        }
        StrongboxPayload payload = coordinator.getLivePayload();
        return payload == null ? -1 : payload.maxWalletIndex();
    }

    private void requireUnlocked() {
        if (!coordinator.isUnlocked()) {
            throw new IllegalStateException("SecureStorage is locked");
        }
    }
}

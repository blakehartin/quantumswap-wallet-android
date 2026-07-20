package com.quantumswap.app.utils;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.keystorage.UnlockCoordinator;
import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.strongbox.StrongboxPayload;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Single source of truth for the user-customizable blockchain
 * network list and the active-network index.
 *
 * <h3>Storage architecture (v=3)</h3>
 * <p>Mirrors iOS {@code BlockchainNetworkManager}: user-added
 * networks live inside {@link StrongboxPayload#customNetworks}
 * (encrypted at rest) and the active-network selection lives in
 * {@link StrongboxPayload#activeNetworkIndex}. Bundled mainnet
 * (and any other built-in network shipped in
 * {@code R.raw.blockchain_networks}) is the read-only baseline
 * that is always re-prepended at runtime — bundled entries are
 * NEVER persisted inside the strongbox payload, so a future
 * change to the bundled list (rename, RPC swap) takes effect
 * for every existing install without a migration. This is the
 * v=3 portable contract: the inner payload contains only
 * user-added networks, byte-for-byte identical to the iOS
 * {@code customNetworks} array.</p>
 *
 * <h3>Migration from the legacy plaintext SharedPreferences</h3>
 * <p>Older builds wrote user-added networks to
 * {@link PrefConnect#BLOCKCHAIN_NETWORK_LIST} as a plaintext JSON
 * blob and the active-index to
 * {@link PrefConnect#BLOCKCHAIN_NETWORK_ID_INDEX_KEY} (also
 * plaintext). After this class is wired into the read/write paths,
 * call {@link #migrateLegacyOnUnlockIfNeeded} once per app session
 * after a successful unlock; it copies any legacy data into the
 * strongbox and clears the prefs keys so the plaintext blob is
 * removed from disk (an Android Auto Backup of {@code shared_prefs}
 * would otherwise keep leaking the network list indefinitely).</p>
 */
public final class NetworkPersistence {

    private NetworkPersistence() { }

    private static final String TAG = "NetworkPersistence";

    // ---------------------------------------------------------------
    // Read path
    // ---------------------------------------------------------------

    /**
     * Preferred network-list read. Always returns the bundled
     * read-only baseline (mainnet etc. shipped in
     * {@code R.raw.blockchain_networks}) followed by any
     * user-added entries from
     * {@link StrongboxPayload#customNetworks} when the strongbox
     * is unlocked. Mirrors iOS
     * {@code BlockchainNetworkManager.applyDecryptedConfig}
     * (custom-suffix bundled).
     */
    @NonNull
    public static List<BlockchainNetwork> readNetworks(@NonNull Context ctx,
                                                       @Nullable SecureStorage secureStorage) {
        List<BlockchainNetwork> bundled = readBundled(ctx);
        List<BlockchainNetwork> out = new ArrayList<>(bundled);
        if (secureStorage != null
                && secureStorage.getCoordinator() != null
                && secureStorage.getCoordinator().isUnlocked()) {
            StrongboxPayload payload = secureStorage.getCoordinator().getLivePayload();
            if (payload != null && payload.customNetworks != null) {
                for (StrongboxPayload.Network n : payload.customNetworks) {
                    out.add(toModel(n));
                }
            }
        }
        return out;
    }

    /**
     * Preferred active-index read. Strongbox first, prefs fallback,
     * 0 default. The returned index is into the merged list returned
     * by {@link #readNetworks} (bundled prefix + user-added suffix);
     * out-of-range values clamp to {@code [0, merged-1]} so a
     * post-upgrade payload that recorded an index into the older
     * user-added-only list doesn't crash and falls back to mainnet.
     */
    public static int readActiveIndex(@NonNull Context ctx,
                                      @Nullable SecureStorage secureStorage) {
        int merged = readNetworks(ctx, secureStorage).size();
        int upperBound = Math.max(0, merged - 1);
        int desired;
        if (secureStorage != null
                && secureStorage.getCoordinator() != null
                && secureStorage.getCoordinator().isUnlocked()) {
            StrongboxPayload payload = secureStorage.getCoordinator().getLivePayload();
            desired = (payload == null) ? 0 : Math.max(0, payload.activeNetworkIndex);
        } else {
            // Locked path: legacy prefs is the only source of truth
            // until migration runs at next unlock. Falls through to 0
            // when the key is absent.
            desired = Math.max(0, PrefConnect.readInteger(ctx,
                    PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY, 0));
        }
        return Math.min(desired, upperBound);
    }

    // ---------------------------------------------------------------
    // Write path
    // ---------------------------------------------------------------

    /**
     * Append {@code blockchainNetwork} to
     * {@link StrongboxPayload#customNetworks} and persist
     * atomically. Caller MUST have already unlocked the
     * strongbox; this method does NOT prompt for the password
     * (see {@link #ensureUnlocked} for the prompt-and-unlock
     * helper used by call sites).
     *
     * @return the index of the newly added entry within the
     *         merged (bundled-prefix + customNetworks) list
     * @throws IllegalStateException if {@code secureStorage} is locked
     * @throws Exception             on any persistence failure (the
     *                               in-memory payload is left
     *                               unchanged on failure)
     */
    public static int persistAddedNetwork(@NonNull Context ctx,
                                          @NonNull SecureStorage secureStorage,
                                          @NonNull BlockchainNetwork blockchainNetwork,
                                          @NonNull String password)
            throws Exception {
        UnlockCoordinator coord = secureStorage.getCoordinator();
        if (coord == null || !coord.isUnlocked()) {
            throw new IllegalStateException(
                    "persistAddedNetwork: strongbox is locked");
        }
        StrongboxPayload payload = coord.getLivePayload();
        if (payload == null) {
            throw new IllegalStateException(
                    "persistAddedNetwork: live payload is null");
        }
        if (payload.customNetworks == null) {
            payload.customNetworks = new ArrayList<>();
        }
        StrongboxPayload.Network entry = toPayload(blockchainNetwork);
        payload.customNetworks.add(entry);
        try {
            coord.persist(ctx, payload, password, /*uiPhase=*/null);
        } catch (Exception e) {
            // Roll back the in-memory mutation so a failed persist
            // cannot leave callers with a phantom entry that
            // disappears on next unlock.
            payload.customNetworks.remove(payload.customNetworks.size() - 1);
            throw e;
        }
        // Index within the merged list: bundled prefix + the new
        // entry's position inside customNetworks.
        int bundledCount = readBundled(ctx).size();
        return bundledCount + payload.customNetworks.size() - 1;
    }

    /**
     * Persist a new active-network index into
     * {@link StrongboxPayload#activeNetworkIndex}. Mirrors iOS
     * {@code BlockchainNetworkManager.setActive(_:password:)}.
     *
     * @throws IllegalStateException if {@code secureStorage} is locked
     */
    public static void writeActiveIndex(@NonNull Context ctx,
                                        @NonNull SecureStorage secureStorage,
                                        int newIndex,
                                        @NonNull String password) throws Exception {
        UnlockCoordinator coord = secureStorage.getCoordinator();
        if (coord == null || !coord.isUnlocked()) {
            throw new IllegalStateException(
                    "writeActiveIndex: strongbox is locked");
        }
        StrongboxPayload payload = coord.getLivePayload();
        if (payload == null) {
            throw new IllegalStateException(
                    "writeActiveIndex: live payload is null");
        }
        if (payload.activeNetworkIndex == newIndex) {
            return;
        }
        int previous = payload.activeNetworkIndex;
        payload.activeNetworkIndex = newIndex;
        try {
            coord.persist(ctx, payload, password, /*uiPhase=*/null);
        } catch (Exception e) {
            payload.activeNetworkIndex = previous;
            throw e;
        }
        // Tombstone the legacy plaintext index now that the
        // strongbox has the canonical value. We do this only after
        // the strongbox write succeeds so a failed write doesn't
        // leave both sources of truth empty.
        try {
            PrefConnect.writeInteger(ctx,
                    PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY, 0);
        } catch (Throwable ignore) { }
    }

    // ---------------------------------------------------------------
    // Migration
    // ---------------------------------------------------------------

    /**
     * One-shot migration of legacy plaintext networks +
     * active-index from {@link PrefConnect} into the strongbox.
     * Idempotent: if {@link StrongboxPayload#customNetworks}
     * already holds entries (because migration ran previously)
     * this method is a no-op; if the legacy prefs blob is empty
     * the method is also a no-op. After a successful migration
     * the legacy keys are cleared so the plaintext blob is
     * removed from disk.
     *
     * <p>Safe to call after every successful unlock. The prefs-clear
     * step makes the second call cheap (legacy blob is empty).</p>
     */
    public static void migrateLegacyOnUnlockIfNeeded(@NonNull Context ctx,
                                                     @NonNull SecureStorage secureStorage,
                                                     @NonNull String password) {
        try {
            UnlockCoordinator coord = secureStorage.getCoordinator();
            if (coord == null || !coord.isUnlocked()) return;
            StrongboxPayload payload = coord.getLivePayload();
            if (payload == null) return;
            if (payload.customNetworks == null) {
                payload.customNetworks = new ArrayList<>();
            }

            String legacyJson = PrefConnect.readString(ctx,
                    PrefConnect.BLOCKCHAIN_NETWORK_LIST, "");
            int legacyIdx = PrefConnect.readInteger(ctx,
                    PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY, 0);

            boolean payloadHasNetworks = !payload.customNetworks.isEmpty();
            boolean legacyHasNetworks = !TextUtils.isEmpty(legacyJson);
            boolean legacyHasIndex = legacyIdx > 0;

            // If neither side has anything new to migrate, nothing
            // to do. (The strongbox active-index already defaults
            // to 0 — no need to copy a 0-prefs-index over.)
            if (!legacyHasNetworks && !legacyHasIndex) return;

            // If the strongbox already holds networks, the legacy
            // blob is stale; just clear it. We do NOT replay the
            // legacy index in this branch because the strongbox
            // index is the canonical value.
            if (payloadHasNetworks) {
                clearLegacyKeys(ctx);
                return;
            }

            // Strongbox is empty AND legacy has data. Copy.
            if (legacyHasNetworks) {
                List<BlockchainNetwork> legacyNetworks = parseLegacyNetworks(legacyJson);
                for (BlockchainNetwork n : legacyNetworks) {
                    payload.customNetworks.add(toPayload(n));
                }
            }
            if (legacyHasIndex) {
                payload.activeNetworkIndex = legacyIdx;
            }
            try {
                coord.persist(ctx, payload, password, /*uiPhase=*/null);
            } catch (Exception persistErr) {
                Timber.w(persistErr, "%s: legacy migration persist failed", TAG);
                // Roll back in-memory side so a subsequent retry
                // can try again on the next unlock.
                payload.customNetworks.clear();
                payload.activeNetworkIndex = 0;
                return;
            }
            clearLegacyKeys(ctx);
            Timber.i("%s: migrated %d legacy networks + index=%d into strongbox",
                    TAG,
                    legacyHasNetworks ? payload.customNetworks.size() : 0,
                    legacyHasIndex ? legacyIdx : 0);
        } catch (Throwable t) {
            // Migration is best-effort. A failure here MUST NOT
            // crash the unlock flow — the legacy reads continue to
            // work, and migration retries on next unlock.
            Timber.w(t, "%s: legacy migration failed (will retry next unlock)", TAG);
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    /** Visible for unit tests; the production call sites use the
     *  high-level {@link #persistAddedNetwork} entry point and do
     *  not invoke this helper directly. */
    @NonNull
    public static StrongboxPayload.Network toPayload(
            @NonNull BlockchainNetwork m) {
        StrongboxPayload.Network n = new StrongboxPayload.Network();
        n.name = m.getBlockchainName() == null ? "" : m.getBlockchainName();
        n.rpcEndpoint = m.getRpcEndpoint() == null ? "" : m.getRpcEndpoint();
        n.scanApiDomain = m.getScanApiDomain() == null ? "" : m.getScanApiDomain();
        n.blockExplorerUrl = m.getBlockExplorerDomain() == null
                ? "" : m.getBlockExplorerDomain();
        n.chainId = m.getNetworkId() == null ? "" : m.getNetworkId().trim();
        return n;
    }

    /** Visible for unit tests; the production read path goes
     *  through {@link #readNetworks(Context, SecureStorage)}. */
    @NonNull
    public static BlockchainNetwork toModel(@NonNull StrongboxPayload.Network n) {
        BlockchainNetwork m = new BlockchainNetwork();
        m.setScanApiDomain(n.scanApiDomain == null ? "" : n.scanApiDomain);
        m.setRpcEndpoint(n.rpcEndpoint == null ? "" : n.rpcEndpoint);
        m.setBlockExplorerDomain(n.blockExplorerUrl == null ? "" : n.blockExplorerUrl);
        m.setBlockchainName(n.name == null ? "" : n.name);
        m.setNetworkId(n.chainId == null ? "" : n.chainId);
        return m;
    }

    @NonNull
    private static List<BlockchainNetwork> parseLegacyNetworks(@NonNull String json)
            throws Exception {
        Object parsed = new JSONTokener(json).nextValue();
        if (!(parsed instanceof JSONObject)) return new ArrayList<>();
        JSONArray arr = ((JSONObject) parsed).optJSONArray("networks");
        if (arr == null) return new ArrayList<>();
        List<BlockchainNetwork> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            BlockchainNetwork n = new BlockchainNetwork();
            n.setScanApiDomain(e.optString("scanApiDomain", "").trim());
            n.setRpcEndpoint(e.optString("rpcEndpoint", "").trim());
            n.setBlockExplorerDomain(e.optString("blockExplorerDomain", "").trim());
            n.setBlockchainName(e.optString("blockchainName", "").trim());
            n.setNetworkId(String.valueOf(e.opt("networkId")).trim());
            out.add(n);
        }
        return out;
    }

    @NonNull
    private static List<BlockchainNetwork> readBundled(@NonNull Context ctx) {
        try {
            String json = GlobalMethods.readRawResource(ctx,
                    com.quantumswap.app.R.raw.blockchain_networks);
            return parseLegacyNetworks(json);
        } catch (Throwable t) {
            Timber.w(t, "%s: bundled network read failed; returning empty list", TAG);
            return new ArrayList<>();
        }
    }

    private static void clearLegacyKeys(@NonNull Context ctx) {
        try {
            PrefConnect.writeString(ctx, PrefConnect.BLOCKCHAIN_NETWORK_LIST, "");
            PrefConnect.writeInteger(ctx,
                    PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY, 0);
        } catch (Throwable t) {
            Timber.w(t, "%s: failed to clear legacy plaintext network keys", TAG);
        }
    }

    /**
     * Convenience for call sites that want to persist a network add
     * but may need to prompt for unlock first. Returns {@code true}
     * if the strongbox was already unlocked (caller should proceed
     * with {@link #persistAddedNetwork} on the same thread); returns
     * {@code false} if the caller MUST present an unlock prompt
     * before persisting.
     */
    public static boolean ensureUnlocked(@NonNull SecureStorage secureStorage) {
        UnlockCoordinator coord = secureStorage.getCoordinator();
        return coord != null && coord.isUnlocked();
    }
}

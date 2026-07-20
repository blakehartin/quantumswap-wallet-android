package com.quantumswap.app.strongbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import com.quantumswap.app.keystorage.MacUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed in-memory representation of the strongbox plaintext.
 *
 * <p>Layer 5 of the storage stack — the highest-level model the
 * crypto layers see. {@link StrongboxFileCodec} (layer 2) is the
 * only place that knows how this object is serialised onto disk,
 * and {@link com.quantumswap.app.storage.AtomicSlotWriter}
 * (layer 1) sees it only as opaque bytes.</p>
 *
 * <p><b>v=3 unified-schema (notes for reviewers):</b>
 * The on-disk plaintext layout below is byte-for-byte identical
 * to iOS {@code StrongboxPayload.swift} under the same inputs.
 * The inner {@code checksum} is a keyed HMAC-SHA-256 derived
 * via {@code HKDF(mainKey, salt=null, info="strongbox-payload-
 * checksum-v3", L=32)}. Test vectors live under
 * {@code tests/fixtures/strongbox-v3-vectors/} and are consumed
 * by {@code StrongboxPortabilityVectorTest} on each platform.</p>
 *
 * <p><b>Wire shape (canonical sortedKeys JSON):</b>
 * <pre>
 * {
 *   "v": 3,
 *   "wallets": { "0": "&lt;base64(walletEntryBlob)&gt;", "1": "...", ... },
 *   "currentWalletIndex": 0,
 *   "customNetworks": [ { "name": "...", "chainId": "...",
 *                         "scanApiDomain": "https://...",
 *                         "rpcEndpoint": "https://...",
 *                         "blockExplorerUrl": "https://..." }, ... ],
 *   "activeNetworkIndex": 0,
 *   "cloudBackupFolderUri": "",
 *   "advancedSigning": false,
 *   "cameraPermissionAskedOnce": false,
 *   "secureItems": { "k1": "&lt;v1 (opaque)&gt;", ... },
 *   "checksum": "&lt;base64(HMAC-SHA-256 over canonical(payload-sans-checksum), key=HKDF(mainKey,...,v3))&gt;"
 * }
 * </pre></p>
 *
 * <p><b>Wallet representation note (notes for reviewers):</b>
 * Each {@code wallets[index]} entry is OPAQUE to this layer;
 * concretely it is the base64-wrapped output of
 * {@link WalletEntryCodec}, which packs the address (UTF-8),
 * the post-quantum {@code privateKey}/{@code publicKey} (raw
 * bytes), and the seed phrase into a length-prefixed binary
 * blob. The compact encoding exists because Dilithium-class
 * keys are ~7.5 KiB raw and a per-wallet JSON-of-base64 wire
 * shape blew past the strongbox bucket once we needed to
 * support &gt;= 256 wallets per install. This payload layer
 * makes no assumptions about the blob contents; the
 * SecureStorage facade is the only caller that round-trips
 * through {@link WalletEntryCodec}.</p>
 *
 * <p><b>Customisation network identity (notes for reviewers):</b>
 * Bundled mainnet/testnet entries live in {@code R.raw.blockchain_networks}
 * and are NOT persisted inside {@code customNetworks}. The
 * unlock path re-prepends them at runtime (see
 * {@code NetworkPersistence.readNetworks}). The {@code activeNetworkIndex}
 * is an index into the merged list (bundled prefix + customNetworks)
 * so the user's selection survives a bundled-list change.</p>
 */
public final class StrongboxPayload {

    /** Schema version stored in the {@code v} field.
     *  v=3 is the cross-platform-portable schema. */
    public static final int SCHEMA_VERSION = 3;

    /** RFC 5869 info string for the inner checksum key. v=3
     *  bumped from "strongbox-payload-checksum-v2" so the
     *  derived key cannot collide across schema versions and
     *  surfaces the schema bump in the derivation context.
     *  Mirrors iOS {@code Strongbox.checksumInfoLabel}. */
    public static final String CHECKSUM_INFO_LABEL = "strongbox-payload-checksum-v3";

    @SerializedName("v")
    public int v = SCHEMA_VERSION;

    /** Index (decimal-string) -&gt; opaque wallet JSON. Sorted
     *  on serialise for byte-stable canonical output. */
    @SerializedName("wallets")
    public Map<String, String> wallets = new LinkedHashMap<>();

    /** Index of the currently-active wallet (one of the
     *  {@code wallets} map keys, parsed as an int). Persisted
     *  so the home strip re-opens to the same wallet across
     *  relaunches. Default 0. */
    @SerializedName("currentWalletIndex")
    public int currentWalletIndex = 0;

    /** User-added custom networks. Bundled mainnet is NOT
     *  included; the unlock path re-prepends it at runtime
     *  from {@code R.raw.blockchain_networks}. Mirrors iOS
     *  {@code StrongboxPayload.customNetworks}. */
    @SerializedName("customNetworks")
    public List<Network> customNetworks = new ArrayList<>();

    /** Index into {@code (bundled || customNetworks)} of the
     *  currently-active network. 0 == bundled mainnet.
     *  Mirrors iOS {@code StrongboxPayload.activeNetworkIndex}. */
    @SerializedName("activeNetworkIndex")
    public int activeNetworkIndex = 0;

    // The `backupEnabled` toggle is intentionally NOT a payload
    // field. `WalletBackupAgent.onCreate` (Android) and the iOS
    // UserDefaults equivalent both run BEFORE the user unlocks the
    // wallet — they only ever read the SharedPreferences pref,
    // never the encrypted payload. Keeping a duplicate copy inside
    // the encrypted payload would create a parity gap (the on-disk
    // pref could disagree with the on-disk payload after a crash
    // during a SharedPreferences -> Strongbox handoff) without any
    // actual consumer reading the payload field. SharedPreferences
    // is the SOLE source of truth for the backup-enabled toggle.
    // Mirrored on iOS in `StrongboxPayload.swift`.

    /** User-selected cloud-backup folder URI for {@code .wallet}
     *  exports. Empty string when no folder is selected. */
    @SerializedName("cloudBackupFolderUri")
    public String cloudBackupFolderUri = "";

    /** User toggle: enable advanced signing UX. */
    @SerializedName("advancedSigning")
    public boolean advancedSigning = false;

    /** Idempotency flag for the camera-permission prompt.
     *  {@code true} once we have asked the user (regardless of
     *  their answer) so we never re-prompt automatically. */
    @SerializedName("cameraPermissionAskedOnce")
    public boolean cameraPermissionAskedOnce = false;

    /** Generic key-&gt;value secure items (e.g. saved per-address
     *  passwords). Each value is opaque to this layer. */
    @SerializedName("secureItems")
    public Map<String, String> secureItems = new LinkedHashMap<>();

    @SerializedName("checksum")
    public String checksum = "";

    /** v=3 cross-platform-portable network entry. Field names
     *  match iOS {@code BlockchainNetwork} exactly so the
     *  canonical JSON is byte-identical between platforms.
     *  Bundled mainnet/testnet are NOT stored here; only
     *  user-added networks. */
    public static final class Network {
        @SerializedName("name") public String name = "";
        @SerializedName("chainId") public String chainId = "";
        @SerializedName("scanApiDomain") public String scanApiDomain = "";
        @SerializedName("rpcEndpoint") public String rpcEndpoint = "";
        @SerializedName("blockExplorerUrl") public String blockExplorerUrl = "";
    }

    /** Largest wallet index currently in use, or -1 if no
     *  wallets exist. Derived from {@code wallets} map keys
     *  on demand (the v=3 schema dropped the redundant
     *  {@code maxWalletIndex} field; deriving here mirrors
     *  iOS {@code Strongbox.maxWalletIndex}). */
    public int maxWalletIndex() {
        int max = -1;
        if (wallets == null) return max;
        for (String k : wallets.keySet()) {
            try {
                int i = Integer.parseInt(k);
                if (i > max) max = i;
            } catch (NumberFormatException ignore) { }
        }
        return max;
    }

    /**
     * Build a canonical sortedKeys JSON byte string of {@code
     * this} with the {@code checksum} field omitted. This is
     * the input to the inner-checksum HMAC.
     */
    public byte[] canonicalBytesForChecksum() {
        return canonicalBytes(/*includeChecksum=*/false);
    }

    private byte[] canonicalBytes(boolean includeChecksum) {
        TreeMap<String, Object> root = new TreeMap<>();
        root.put("v", v);

        TreeMap<String, String> sortedWallets = new TreeMap<>(
                wallets == null ? Collections.<String, String>emptyMap() : wallets);
        root.put("wallets", sortedWallets);

        root.put("currentWalletIndex", currentWalletIndex);

        List<Object> networkObjs = new ArrayList<>();
        if (customNetworks != null) {
            for (Network n : customNetworks) {
                TreeMap<String, Object> no = new TreeMap<>();
                no.put("name", n.name == null ? "" : n.name);
                no.put("chainId", n.chainId == null ? "" : n.chainId);
                no.put("scanApiDomain", n.scanApiDomain == null ? "" : n.scanApiDomain);
                no.put("rpcEndpoint", n.rpcEndpoint == null ? "" : n.rpcEndpoint);
                no.put("blockExplorerUrl", n.blockExplorerUrl == null ? "" : n.blockExplorerUrl);
                networkObjs.add(no);
            }
        }
        root.put("customNetworks", networkObjs);

        root.put("activeNetworkIndex", activeNetworkIndex);
        root.put("cloudBackupFolderUri", cloudBackupFolderUri == null ? "" : cloudBackupFolderUri);
        root.put("advancedSigning", advancedSigning);
        root.put("cameraPermissionAskedOnce", cameraPermissionAskedOnce);

        TreeMap<String, String> sortedSecureItems = new TreeMap<>(
                secureItems == null ? Collections.<String, String>emptyMap() : secureItems);
        root.put("secureItems", sortedSecureItems);

        if (includeChecksum) {
            root.put("checksum", checksum == null ? "" : checksum);
        }

        // Use the codec's canonical writer to byte-match iOS
        // JSONSerialization sortedKeys output. Gson with default
        // settings would emit Long values as decimals with no
        // trailing .0 (good) but we route through writeCanonical
        // for explicit control of every emitted token (matches
        // the rest of the codec).
        return StrongboxFileCodec.canonicalize(root);
    }

    /**
     * Compute the inner checksum (HMAC-SHA-256 over the
     * canonical bytes-sans-checksum, keyed by
     * {@code HKDF(mainKey, salt=null, info=CHECKSUM_INFO_LABEL, 32)}).
     */
    public byte[] computeChecksum(byte[] mainKey) {
        byte[] checksumKey = MacUtil.hkdfExtractAndExpand(
                mainKey, null, CHECKSUM_INFO_LABEL, MacUtil.HMAC_LEN);
        try {
            return MacUtil.hmacSha256(canonicalBytesForChecksum(), checksumKey);
        } finally {
            for (int i = 0; i < checksumKey.length; i++) checksumKey[i] = 0;
        }
    }

    /** Verify the {@code checksum} field against
     *  {@link #computeChecksum(byte[])} in constant time. */
    public boolean verifyChecksum(byte[] mainKey) {
        if (checksum == null || checksum.isEmpty()) return false;
        byte[] expected;
        try {
            // RFC 4648 base64 (no line wrapping) — matches iOS
            // Data.base64EncodedString() default and the on-disk
            // checksum field across both platforms. We use
            // java.util.Base64 (not android.util.Base64) so this
            // path is exercised by pure-JVM unit tests; the wire
            // format is identical.
            expected = Base64.getDecoder().decode(checksum);
        } catch (IllegalArgumentException iae) {
            return false;
        }
        byte[] actual = computeChecksum(mainKey);
        return MessageDigest.isEqual(actual, expected);
    }

    /** Stamp {@code checksum} from the current state. */
    public void stampChecksum(byte[] mainKey) {
        // RFC 4648 base64 without padding line breaks; see
        // verifyChecksum for the platform-portability rationale.
        this.checksum = Base64.getEncoder().encodeToString(
                computeChecksum(mainKey));
    }

    /** Encode (with {@code checksum} stamped) for AEAD-seal. */
    public byte[] encodeForSeal(byte[] mainKey) {
        stampChecksum(mainKey);
        return canonicalBytes(/*includeChecksum=*/true);
    }

    /** Decode AEAD-opened plaintext back into a typed payload. */
    public static StrongboxPayload decode(byte[] plaintext) {
        Gson gson = new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
        String json = new String(plaintext, StandardCharsets.UTF_8);
        StrongboxPayload p = gson.fromJson(json, StrongboxPayload.class);
        if (p == null) p = new StrongboxPayload();
        if (p.wallets == null) p.wallets = new LinkedHashMap<>();
        if (p.customNetworks == null) p.customNetworks = new ArrayList<>();
        if (p.cloudBackupFolderUri == null) p.cloudBackupFolderUri = "";
        if (p.secureItems == null) p.secureItems = new LinkedHashMap<>();
        return p;
    }
}
